package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.LobbyMapCompensationResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyMapRequest;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LobbyMapUpdateService의 맵 변경 정책을 검증한다.
 *
 * [테스트 범위]
 * - principal / userId / userIdentifier 검증
 * - 로비 미존재 처리
 * - WAITING 상태 제한 (Redis 1차 / DB 락 후 재검증)
 * - 방장 권한 제한
 * - 정상 변경 시 Redis/DB 갱신 및 realtime 이벤트 등록
 * - DB 갱신 실패 시 compensateMapMetadataIfWaiting 호출
 * - DB 조건부 갱신 0행 시 보상 + 409
 * - 기존 맵 미선택 로비에서 DB 실패 시 보상이 null oldMetadata로 호출됨
 * - DB GAME_LOBBY 스냅샷 누락 시 reconciliation 패턴 적용
 */
class LobbyMapUpdateServiceTest {

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final GameLobbyJpaRepository gameLobbyJpaRepository = mock(GameLobbyJpaRepository.class);
    private final LobbyMapPolicy lobbyMapPolicy = mock(LobbyMapPolicy.class);
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier = mock(LobbyRealtimeNotifier.class);

    private final LobbyMapUpdateService sut = new LobbyMapUpdateService(
            lobbyRepository,
            gameLobbyJpaRepository,
            lobbyMapPolicy,
            lobbyRealtimeNotifier
    );

    private static final String CODE = "ABC123";
    private static final String HOST_ID = "host-uuid";
    private static final Long USER_ID = 1L;

    private CustomPrincipal hostPrincipal() {
        return new CustomPrincipal(USER_ID, HOST_ID, UserType.REGISTERED);
    }

    private JoinLobbyResponse waitingLobby() {
        return JoinLobbyResponse.builder()
                .inviteCode(CODE)
                .title("테스트 로비")
                .hostId(HOST_ID)
                .maxPlayers(6)
                .currentPlayers(2)
                .status("WAITING")
                .mapId(10L)
                .mapTitle("K-POP 구곡")
                .mapCategory("K-POP")
                .build();
    }

    private JoinLobbyResponse waitingLobbyWithNoMap() {
        return JoinLobbyResponse.builder()
                .inviteCode(CODE)
                .title("테스트 로비")
                .hostId(HOST_ID)
                .maxPlayers(6)
                .currentPlayers(2)
                .status("WAITING")
                .mapId(null)
                .mapTitle(null)
                .mapCategory(null)
                .build();
    }

    private GameLobby gameLobbyWith(LobbyStatus status, Long mapId) {
        return GameLobby.builder()
                .id(100L)
                .host(User.builder().id(USER_ID).build())
                .mapId(mapId)
                .inviteCode(CODE)
                .title("테스트 로비")
                .maxPlayers(6)
                .roundCount(5)
                .timeLimitSeconds(30)
                .isPrivate(false)
                .status(status)
                .isDeleted(false)
                .build();
    }

    private UpdateLobbyMapRequest request(long mapId) {
        return new UpdateLobbyMapRequest(mapId);
    }

    // ─── principal 검증 ───────────────────────────────────

    @Test
    @DisplayName("principal이 null이면 401 Unauthorized를 던진다")
    void updateMap_throwsUnauthorized_whenPrincipalIsNull() {
        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("principal.userId()가 null이면 401 Unauthorized를 던진다")
    void updateMap_throwsUnauthorized_whenUserIdIsNull() {
        CustomPrincipal principal = new CustomPrincipal(null, HOST_ID, UserType.REGISTERED);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("principal.userIdentifier()가 null이면 NPE 없이 401 Unauthorized를 던진다")
    void updateMap_throwsUnauthorized_whenUserIdentifierIsNull() {
        CustomPrincipal principal = new CustomPrincipal(USER_ID, null, UserType.REGISTERED);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ─── 로비 조회 검증 ──────────────────────────────────

    @Test
    @DisplayName("Redis에 로비가 없으면 404 Not Found를 던진다")
    void updateMap_throwsNotFound_whenLobbyNotFound() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ─── 상태 검증 ────────────────────────────────────────

    @Test
    @DisplayName("Redis 상태가 PLAYING이면 409 Conflict를 던진다 (DB 락 획득 전 차단)")
    void updateMap_throwsConflict_whenLobbyNotWaiting() {
        JoinLobbyResponse playingLobby = JoinLobbyResponse.builder()
                .inviteCode(CODE)
                .title("테스트 로비")
                .hostId(HOST_ID)
                .maxPlayers(6)
                .currentPlayers(4)
                .status("PLAYING")
                .build();

        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(playingLobby));

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(gameLobbyJpaRepository, never()).findByInviteCodeForUpdate(any());
    }

    @Test
    @DisplayName("Redis는 WAITING이지만 DB 락 획득 후 status가 PLAYING이면 409를 던진다")
    void updateMap_throwsConflict_whenDbStatusIsPlayingAfterLockAcquired() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.PLAYING, 10L)));

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository, never()).updateMapMetadata(any(), any());
    }

    // ─── 방장 검증 ────────────────────────────────────────

    @Test
    @DisplayName("방장이 아닌 사용자가 요청하면 403 Forbidden을 던진다")
    void updateMap_throwsForbidden_whenRequesterIsNotHost() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));

        CustomPrincipal nonHost = new CustomPrincipal(2L, "other-uuid", UserType.REGISTERED);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), nonHost))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─── 락 충돌 ─────────────────────────────────────────

    @Test
    @DisplayName("DB row lock 획득이 타임아웃되면 409 CONFLICT로 변환한다")
    void updateMap_throwsConflict_whenLockAcquisitionTimesOut() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenThrow(new PessimisticLockingFailureException("lock wait timeout"));

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("진행 중");
                });

        verify(lobbyRepository, never()).updateMapMetadata(any(), any());
        verify(lobbyRepository, never()).compensateMapMetadataIfWaiting(any(), any());
    }

    // ─── DB 스냅샷 누락 ─────────────────────────────────

    @Test
    @DisplayName("DB GAME_LOBBY 스냅샷이 없으면 Redis 보상 삭제 후 409를 던진다")
    void updateMap_handlesMissingDbSnapshot_byDeletingRedisAndThrowingConflict() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE)).thenReturn(Optional.empty());
        when(lobbyRepository.deleteFromRedis(CODE)).thenReturn(true);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository).deleteFromRedis(CODE);
        verify(lobbyRepository, never()).enqueueStartReconciliation(any(), any());
        verify(lobbyRepository, never()).updateMapMetadata(any(), any());
    }

    @Test
    @DisplayName("DB 스냅샷 누락 + Redis 삭제 실패 시 reconciliation 큐에 적재한다")
    void updateMap_enqueuesReconciliation_whenSnapshotMissingAndRedisDeleteFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE)).thenReturn(Optional.empty());
        when(lobbyRepository.deleteFromRedis(CODE)).thenReturn(false);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository).enqueueStartReconciliation(eq(CODE), eq("MAP_UPDATE_DB_SNAPSHOT_NOT_FOUND"));
    }

    // ─── 정상 변경 ───────────────────────────────────────

    @Test
    @DisplayName("정상 요청 시 Redis/DB를 갱신하고 afterCommit 이벤트를 등록한다")
    void updateMap_updatesRedisAndDb_andRegistersAfterCommitEvent() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
            LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
            when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
            when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                    .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
            when(gameLobbyJpaRepository.updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING)).thenReturn(1);

            sut.updateMap(CODE, request(2L), hostPrincipal());

            verify(lobbyRepository).updateMapMetadata(CODE, newMetadata);
            verify(gameLobbyJpaRepository).updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING);
            verify(lobbyRepository, never()).compensateMapMetadataIfWaiting(any(), any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(CODE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ─── DB 실패 시 보상 복구 ──────────────────────────────

    @Test
    @DisplayName("DB 갱신 실패 시 compensateMapMetadataIfWaiting으로 Redis 보상을 시도하고 500을 던진다")
    void updateMap_rollbacksRedis_whenDbSaveFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(eq(CODE), eq(2L), eq(LobbyStatus.WAITING)))
                .thenThrow(new RuntimeException("DB 장애"));
        when(lobbyRepository.compensateMapMetadataIfWaiting(eq(CODE), any()))
                .thenReturn(LobbyMapCompensationResult.COMPENSATED);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        LobbyMapMetadata oldMetadata = new LobbyMapMetadata(10L, "K-POP 구곡", "K-POP");
        verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(newMetadata));
        verify(lobbyRepository).compensateMapMetadataIfWaiting(eq(CODE), eq(oldMetadata));
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(any());
    }

    @Test
    @DisplayName("기존 맵 미선택 로비에서 DB 실패 시 보상이 null oldMetadata로 호출된다")
    void updateMap_rollbacksRedisWithNull_whenNoMapLobbyAndDbFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobbyWithNoMap()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(1L, "K-POP 히트곡", "K-POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(1L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, null)));
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(eq(CODE), eq(1L), eq(LobbyStatus.WAITING)))
                .thenThrow(new RuntimeException("DB 장애"));
        when(lobbyRepository.compensateMapMetadataIfWaiting(eq(CODE), any()))
                .thenReturn(LobbyMapCompensationResult.COMPENSATED);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(newMetadata));
        verify(lobbyRepository).compensateMapMetadataIfWaiting(eq(CODE), (LobbyMapMetadata) isNull());
    }

    @Test
    @DisplayName("DB 조건부 갱신이 0행 반환이면 Redis를 보상 복구하고 409를 던진다")
    void updateMap_rollbacksRedisAndThrowsConflict_whenDbUpdatedZeroRows() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING)).thenReturn(0);
        when(lobbyRepository.compensateMapMetadataIfWaiting(eq(CODE), any()))
                .thenReturn(LobbyMapCompensationResult.COMPENSATED);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        LobbyMapMetadata oldMetadata = new LobbyMapMetadata(10L, "K-POP 구곡", "K-POP");
        verify(lobbyRepository).compensateMapMetadataIfWaiting(eq(CODE), eq(oldMetadata));
    }

    @Test
    @DisplayName("보상 Lua가 SKIPPED_NOT_WAITING을 반환해도 호출자 예외 응답은 그대로 유지된다")
    void updateMap_handlesSkippedCompensation_whenStatusNoLongerWaiting() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING)).thenReturn(0);
        when(lobbyRepository.compensateMapMetadataIfWaiting(eq(CODE), any()))
                .thenReturn(LobbyMapCompensationResult.SKIPPED_NOT_WAITING);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository).compensateMapMetadataIfWaiting(eq(CODE), any());
    }
}
