package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbyMapRequest;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * - principal/userId 검증
 * - 로비 미존재 처리
 * - WAITING 상태 제한
 * - 방장 권한 제한
 * - 정상 변경 시 Redis/DB 갱신 및 realtime 이벤트 등록
 * - DB 갱신 실패 시 Redis 보상 복구
 * - DB 조건부 갱신 0행(레이스) 시 Redis 보상 복구 + 409
 * - 기존 맵 미선택 로비에서 DB 실패 시 보상 복구가 null로 호출됨
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
    @DisplayName("로비 상태가 PLAYING이면 409 Conflict를 던진다")
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

    // ─── 정상 변경 ───────────────────────────────────────

    @Test
    @DisplayName("정상 요청 시 Redis/DB를 갱신하고 afterCommit 이벤트를 등록한다")
    void updateMap_updatesRedisAndDb_andRegistersAfterCommitEvent() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));

            LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
            when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
            when(gameLobbyJpaRepository.updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING)).thenReturn(1);

            sut.updateMap(CODE, request(2L), hostPrincipal());

            verify(lobbyRepository).updateMapMetadata(CODE, newMetadata);
            verify(gameLobbyJpaRepository).updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING);
            // afterCommit 등록 후 커밋 시뮬레이션
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(CODE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ─── DB 실패 시 보상 복구 ──────────────────────────────

    @Test
    @DisplayName("DB 갱신 실패 시 Redis를 이전 맵 메타데이터로 보상 복구하고 500을 던진다")
    void updateMap_rollbacksRedis_whenDbSaveFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));

        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(eq(CODE), eq(2L), eq(LobbyStatus.WAITING)))
                .thenThrow(new RuntimeException("DB 장애"));

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        LobbyMapMetadata oldMetadata = new LobbyMapMetadata(10L, "K-POP 구곡", "K-POP");
        var inOrder = inOrder(lobbyRepository);
        inOrder.verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(newMetadata));
        inOrder.verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(oldMetadata));

        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(any());
    }

    @Test
    @DisplayName("기존 맵 미선택 로비에서 DB 갱신 실패 시 보상 복구가 updateMapMetadata(code, null)로 호출된다")
    void updateMap_rollbacksRedisWithNull_whenNoMapLobbyAndDbFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobbyWithNoMap()));

        LobbyMapMetadata newMetadata = new LobbyMapMetadata(1L, "K-POP 히트곡", "K-POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(1L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(eq(CODE), eq(1L), eq(LobbyStatus.WAITING)))
                .thenThrow(new RuntimeException("DB 장애"));

        assertThatThrownBy(() -> sut.updateMap(CODE, request(1L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        var inOrder = inOrder(lobbyRepository);
        inOrder.verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(newMetadata));
        inOrder.verify(lobbyRepository).updateMapMetadata(eq(CODE), (LobbyMapMetadata) isNull());

        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(any());
    }

    @Test
    @DisplayName("DB 조건부 갱신이 0행 반환(레이스 컨디션)이면 Redis를 보상 복구하고 409를 던진다")
    void updateMap_rollbacksRedisAndThrowsConflict_whenDbUpdatedZeroRows() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));

        LobbyMapMetadata newMetadata = new LobbyMapMetadata(2L, "POP 히트곡", "POP");
        when(lobbyMapPolicy.resolveLobbyMapMetadata(2L, USER_ID)).thenReturn(newMetadata);
        when(gameLobbyJpaRepository.updateMapIdIfWaiting(CODE, 2L, LobbyStatus.WAITING)).thenReturn(0);

        assertThatThrownBy(() -> sut.updateMap(CODE, request(2L), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        LobbyMapMetadata oldMetadata = new LobbyMapMetadata(10L, "K-POP 구곡", "K-POP");
        var inOrder = inOrder(lobbyRepository);
        inOrder.verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(newMetadata));
        inOrder.verify(lobbyRepository).updateMapMetadata(eq(CODE), eq(oldMetadata));

        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(any());
    }
}
