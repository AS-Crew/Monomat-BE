package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.LobbySettingsRestoreResult;
import io.github.ascrew.monomatbe.domain.lobby.LobbySettingsUpdateResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbySettingsRequest;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbySettingsReconciliationRepository;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbySettingsUpdateServiceTest {

    private static final String CODE = "ABC123";
    private static final String HOST_ID = "host-uuid";
    private static final Long USER_ID = 1L;

    private static final int OLD_MAX_PLAYERS = 6;
    private static final int OLD_QUESTION_COUNT = 5;
    private static final int OLD_TIME_LIMIT_SECONDS = 30;

    private static final int NEW_MAX_PLAYERS = 4;
    private static final int NEW_QUESTION_COUNT = 10;
    private static final int NEW_TIME_LIMIT_SECONDS = 45;

    private static final String SETTINGS_SNAPSHOT_NOT_FOUND_REASON =
            "SETTINGS_UPDATE_DB_SNAPSHOT_NOT_FOUND";
    private static final String SETTINGS_RESTORE_FAILED_REASON =
            "SETTINGS_UPDATE_RESTORE_FAILED:MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS";
    private static final String SETTINGS_RESTORE_EXCEPTION_REASON =
            "SETTINGS_UPDATE_RESTORE_FAILED:EXCEPTION";

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final GameLobbyJpaRepository gameLobbyJpaRepository = mock(GameLobbyJpaRepository.class);
    private final QuizMapJpaRepository quizMapJpaRepository = mock(QuizMapJpaRepository.class);
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier = mock(LobbyRealtimeNotifier.class);
    private final LobbySettingsReconciliationRepository lobbySettingsReconciliationRepository =
            mock(LobbySettingsReconciliationRepository.class);

    private final LobbySettingsUpdateService sut = new LobbySettingsUpdateService(
            lobbyRepository,
            gameLobbyJpaRepository,
            quizMapJpaRepository,
            lobbyRealtimeNotifier,
            lobbySettingsReconciliationRepository
    );

    private CustomPrincipal hostPrincipal() {
        return new CustomPrincipal(USER_ID, HOST_ID, UserType.REGISTERED);
    }

    private UpdateLobbySettingsRequest validRequest() {
        return new UpdateLobbySettingsRequest(
                NEW_MAX_PLAYERS,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        );
    }

    private JoinLobbyResponse waitingLobby() {
        return JoinLobbyResponse.builder()
                .inviteCode(CODE)
                .title("테스트 로비")
                .hostId(HOST_ID)
                .maxPlayers(OLD_MAX_PLAYERS)
                .currentPlayers(2)
                .status("WAITING")
                .mapId(10L)
                .mapTitle("K-POP 구곡")
                .mapCategory("K-POP")
                .questionCount(OLD_QUESTION_COUNT)
                .timeLimitSeconds(OLD_TIME_LIMIT_SECONDS)
                .build();
    }

    private JoinLobbyResponse waitingLobbyWithNoMap() {
        return JoinLobbyResponse.builder()
                .inviteCode(CODE)
                .title("테스트 로비")
                .hostId(HOST_ID)
                .maxPlayers(OLD_MAX_PLAYERS)
                .currentPlayers(2)
                .status("WAITING")
                .mapId(null)
                .mapTitle(null)
                .mapCategory(null)
                .questionCount(OLD_QUESTION_COUNT)
                .timeLimitSeconds(OLD_TIME_LIMIT_SECONDS)
                .build();
    }

    private JoinLobbyResponse playingLobby() {
        return JoinLobbyResponse.builder()
                .inviteCode(CODE)
                .title("테스트 로비")
                .hostId(HOST_ID)
                .maxPlayers(OLD_MAX_PLAYERS)
                .currentPlayers(2)
                .status("PLAYING")
                .mapId(10L)
                .questionCount(OLD_QUESTION_COUNT)
                .timeLimitSeconds(OLD_TIME_LIMIT_SECONDS)
                .build();
    }

    private GameLobby gameLobbyWith(LobbyStatus status, Long mapId) {
        return GameLobby.builder()
                .id(100L)
                .host(User.builder().id(USER_ID).build())
                .mapId(mapId)
                .inviteCode(CODE)
                .title("테스트 로비")
                .maxPlayers(OLD_MAX_PLAYERS)
                .questionCount(OLD_QUESTION_COUNT)
                .timeLimitSeconds(OLD_TIME_LIMIT_SECONDS)
                .isPrivate(false)
                .status(status)
                .isDeleted(false)
                .build();
    }

    private void givenSelectedMapHasNumOfSong(Long mapId, int numOfSong) {
        QuizMap quizMap = QuizMap.builder()
                .id(mapId)
                .numOfSong(numOfSong)
                .isDeleted(false)
                .build();

        when(quizMapJpaRepository.findById(mapId)).thenReturn(Optional.of(quizMap));
    }

    private void givenRedisSettingsUpdateSucceeds() {
        when(lobbyRepository.updateSettings(
                CODE,
                NEW_MAX_PLAYERS,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsUpdateResult.UPDATED);
    }

    private void givenRedisSettingsRestoreSucceeds() {
        when(lobbyRepository.restoreSettings(
                CODE,
                OLD_MAX_PLAYERS,
                OLD_QUESTION_COUNT,
                OLD_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsRestoreResult.RESTORED);
    }

    @Test
    @DisplayName("principal이 null이면 401 Unauthorized를 던진다")
    void updateSettings_throwsUnauthorized_whenPrincipalIsNull() {
        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(lobbyRepository, never()).findByInviteCode(anyString());
    }

    @Test
    @DisplayName("principal.userId()가 null이면 401 Unauthorized를 던진다")
    void updateSettings_throwsUnauthorized_whenUserIdIsNull() {
        CustomPrincipal principal = new CustomPrincipal(null, HOST_ID, UserType.REGISTERED);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(lobbyRepository, never()).findByInviteCode(anyString());
    }

    @Test
    @DisplayName("principal.userIdentifier()가 null이면 NPE 없이 401 Unauthorized를 던진다")
    void updateSettings_throwsUnauthorized_whenUserIdentifierIsNull() {
        CustomPrincipal principal = new CustomPrincipal(USER_ID, null, UserType.REGISTERED);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(lobbyRepository, never()).findByInviteCode(anyString());
    }

    @Test
    @DisplayName("Redis에 로비가 없으면 404 Not Found를 던진다")
    void updateSettings_throwsNotFound_whenLobbyNotFound() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(gameLobbyJpaRepository, never()).findByInviteCodeForUpdate(anyString());
    }

    @Test
    @DisplayName("Redis 상태가 PLAYING이면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenRedisLobbyStatusIsPlaying() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(playingLobby()));

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(gameLobbyJpaRepository, never()).findByInviteCodeForUpdate(anyString());
        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("방장이 아닌 사용자가 요청하면 403 Forbidden을 던진다")
    void updateSettings_throwsForbidden_whenRequesterIsNotHost() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));

        CustomPrincipal nonHost = new CustomPrincipal(2L, "other-uuid", UserType.REGISTERED);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), nonHost))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(gameLobbyJpaRepository, never()).findByInviteCodeForUpdate(anyString());
    }

    @Test
    @DisplayName("maxPlayers가 현재 참가자 수보다 작으면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenMaxPlayersLessThanCurrentPlayers() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(5);

        UpdateLobbySettingsRequest request = new UpdateLobbySettingsRequest(
                4,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        );

        assertThatThrownBy(() -> sut.updateSettings(CODE, request, hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(gameLobbyJpaRepository, never()).findByInviteCodeForUpdate(anyString());
        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("선택된 맵의 등록 곡 수보다 questionCount가 크면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenQuestionCountExceedsSelectedMapSongCount() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        givenSelectedMapHasNumOfSong(10L, 8);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("선택된 맵 ID가 DB에 없으면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenSelectedMapNotFound() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        when(quizMapJpaRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("선택된 맵이 삭제 상태이면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenSelectedMapIsDeleted() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));

        QuizMap deletedMap = QuizMap.builder()
                .id(10L)
                .numOfSong(12)
                .isDeleted(true)
                .build();

        when(quizMapJpaRepository.findById(10L)).thenReturn(Optional.of(deletedMap));

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("선택된 맵의 numOfSong이 null이면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenSelectedMapSongCountIsNull() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));

        QuizMap mapWithNullSongCount = QuizMap.builder()
                .id(10L)
                .numOfSong(null)
                .isDeleted(false)
                .build();

        when(quizMapJpaRepository.findById(10L)).thenReturn(Optional.of(mapWithNullSongCount));

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Redis는 WAITING이지만 DB 락 획득 후 status가 PLAYING이면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenDbStatusIsPlayingAfterLockAcquired() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.PLAYING, 10L)));

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(quizMapJpaRepository, never()).findById(anyLong());
        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("DB row lock 획득이 타임아웃되면 409 Conflict로 변환한다")
    void updateSettings_throwsConflict_whenLockAcquisitionTimesOut() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenThrow(new PessimisticLockingFailureException("lock wait timeout"));

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("진행 중");
                });

        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("DB GAME_LOBBY 스냅샷이 없으면 Redis 보상 삭제 후 409를 던진다")
    void updateSettings_handlesMissingDbSnapshot_byDeletingRedisAndThrowingConflict() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE)).thenReturn(Optional.empty());
        when(lobbyRepository.deleteFromRedis(CODE)).thenReturn(true);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbyRepository).deleteFromRedis(CODE);
        verify(lobbyRepository, never()).enqueueStartReconciliation(anyString(), anyString());
        verify(lobbySettingsReconciliationRepository, never()).enqueueSettingsReconciliation(anyString(), anyString());
        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("DB 스냅샷 누락 + Redis 삭제 실패 시 설정 재처리 큐에 적재한다")
    void updateSettings_enqueuesSettingsReconciliation_whenSnapshotMissingAndRedisDeleteFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE)).thenReturn(Optional.empty());
        when(lobbyRepository.deleteFromRedis(CODE)).thenReturn(false);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(lobbySettingsReconciliationRepository).enqueueSettingsReconciliation(
                eq(CODE),
                eq(SETTINGS_SNAPSHOT_NOT_FOUND_REASON)
        );
        verify(lobbyRepository, never()).enqueueStartReconciliation(anyString(), anyString());
        verify(lobbyRepository, never()).updateSettings(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Redis 설정 변경 결과가 NOT_WAITING이면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenRedisSettingsUpdateReturnsNotWaiting() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        givenSelectedMapHasNumOfSong(10L, 12);

        when(lobbyRepository.updateSettings(
                CODE,
                NEW_MAX_PLAYERS,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsUpdateResult.NOT_WAITING);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Redis 설정 변경 결과가 MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS이면 409 Conflict를 던진다")
    void updateSettings_throwsConflict_whenRedisSettingsUpdateReturnsMaxPlayersLessThanCurrentPlayers() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        givenSelectedMapHasNumOfSong(10L, 12);

        when(lobbyRepository.updateSettings(
                CODE,
                NEW_MAX_PLAYERS,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsUpdateResult.MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Redis 설정 변경 결과가 ERROR이면 500 Internal Server Error를 던진다")
    void updateSettings_throwsInternalServerError_whenRedisSettingsUpdateReturnsError() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));
        givenSelectedMapHasNumOfSong(10L, 12);

        when(lobbyRepository.updateSettings(
                CODE,
                NEW_MAX_PLAYERS,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsUpdateResult.ERROR);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        verify(gameLobbyJpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("정상 요청 시 Redis/DB 설정을 갱신하고 afterCommit refresh 이벤트를 등록한다")
    void updateSettings_updatesRedisAndDb_andRegistersAfterCommitEvent() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
            when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);

            GameLobby gameLobby = gameLobbyWith(LobbyStatus.WAITING, 10L);
            when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                    .thenReturn(Optional.of(gameLobby));

            givenSelectedMapHasNumOfSong(10L, 12);
            givenRedisSettingsUpdateSucceeds();

            when(gameLobbyJpaRepository.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            sut.updateSettings(CODE, validRequest(), hostPrincipal());

            verify(lobbyRepository).updateSettings(
                    CODE,
                    NEW_MAX_PLAYERS,
                    NEW_QUESTION_COUNT,
                    NEW_TIME_LIMIT_SECONDS
            );
            verify(gameLobbyJpaRepository).saveAndFlush(gameLobby);

            assertThat(gameLobby.getMaxPlayers()).isEqualTo(NEW_MAX_PLAYERS);
            assertThat(gameLobby.getQuestionCount()).isEqualTo(NEW_QUESTION_COUNT);
            assertThat(gameLobby.getTimeLimitSeconds()).isEqualTo(NEW_TIME_LIMIT_SECONDS);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(CODE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("맵이 선택되지 않은 로비도 questionCount 범위 검증만 통과하면 설정을 변경한다")
    void updateSettings_updatesSettings_whenLobbyHasNoSelectedMap() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobbyWithNoMap()));
            when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);

            GameLobby gameLobby = gameLobbyWith(LobbyStatus.WAITING, null);
            when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                    .thenReturn(Optional.of(gameLobby));

            givenRedisSettingsUpdateSucceeds();

            when(gameLobbyJpaRepository.saveAndFlush(any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            sut.updateSettings(CODE, validRequest(), hostPrincipal());

            verify(quizMapJpaRepository, never()).findById(anyLong());
            verify(lobbyRepository).updateSettings(
                    CODE,
                    NEW_MAX_PLAYERS,
                    NEW_QUESTION_COUNT,
                    NEW_TIME_LIMIT_SECONDS
            );
            verify(gameLobbyJpaRepository).saveAndFlush(gameLobby);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(CODE);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("DB 갱신 실패 시 Redis 설정값을 이전 값으로 보상 복구하고 500을 던진다")
    void updateSettings_rollbacksRedisSettings_whenDbSaveFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));

        givenSelectedMapHasNumOfSong(10L, 12);
        givenRedisSettingsUpdateSucceeds();
        givenRedisSettingsRestoreSucceeds();

        when(gameLobbyJpaRepository.saveAndFlush(any()))
                .thenThrow(new RuntimeException("DB 장애"));

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        verify(lobbyRepository).updateSettings(
                CODE,
                NEW_MAX_PLAYERS,
                NEW_QUESTION_COUNT,
                NEW_TIME_LIMIT_SECONDS
        );
        verify(lobbyRepository).restoreSettings(
                CODE,
                OLD_MAX_PLAYERS,
                OLD_QUESTION_COUNT,
                OLD_TIME_LIMIT_SECONDS
        );
        verify(lobbySettingsReconciliationRepository, never()).enqueueSettingsReconciliation(
                anyString(),
                anyString()
        );
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(anyString());
    }

    @Test
    @DisplayName("DB 실패 후 Redis 보상 복구 결과가 실패이면 설정 재처리 큐에 적재하고 원래 500 응답을 유지한다")
    void updateSettings_enqueuesSettingsReconciliation_whenRedisCompensationResultFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));

        givenSelectedMapHasNumOfSong(10L, 12);
        givenRedisSettingsUpdateSucceeds();

        when(gameLobbyJpaRepository.saveAndFlush(any()))
                .thenThrow(new RuntimeException("DB 장애"));

        when(lobbyRepository.restoreSettings(
                CODE,
                OLD_MAX_PLAYERS,
                OLD_QUESTION_COUNT,
                OLD_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsRestoreResult.MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS);

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        verify(lobbyRepository).restoreSettings(
                CODE,
                OLD_MAX_PLAYERS,
                OLD_QUESTION_COUNT,
                OLD_TIME_LIMIT_SECONDS
        );
        verify(lobbySettingsReconciliationRepository).enqueueSettingsReconciliation(
                eq(CODE),
                eq(SETTINGS_RESTORE_FAILED_REASON)
        );
        verify(lobbyRepository, never()).enqueueStartReconciliation(anyString(), anyString());
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(anyString());
    }

    @Test
    @DisplayName("DB 실패 후 Redis 보상 복구 중 예외가 발생하면 설정 재처리 큐에 적재하고 원래 500 응답을 유지한다")
    void updateSettings_enqueuesSettingsReconciliation_whenRedisCompensationThrowsException() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));

        givenSelectedMapHasNumOfSong(10L, 12);
        givenRedisSettingsUpdateSucceeds();

        when(gameLobbyJpaRepository.saveAndFlush(any()))
                .thenThrow(new RuntimeException("DB 장애"));

        doThrow(new RuntimeException("Redis 보상 실패"))
                .when(lobbyRepository)
                .restoreSettings(
                        CODE,
                        OLD_MAX_PLAYERS,
                        OLD_QUESTION_COUNT,
                        OLD_TIME_LIMIT_SECONDS
                );

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        verify(lobbySettingsReconciliationRepository).enqueueSettingsReconciliation(
                eq(CODE),
                eq(SETTINGS_RESTORE_EXCEPTION_REASON)
        );
        verify(lobbyRepository, never()).enqueueStartReconciliation(anyString(), anyString());
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(anyString());
    }

    @Test
    @DisplayName("Redis 보상 복구 실패 후 설정 재처리 큐 적재도 실패해도 원래 500 응답을 유지한다")
    void updateSettings_keepsInternalServerError_whenSettingsReconciliationEnqueueFails() {
        when(lobbyRepository.findByInviteCode(CODE)).thenReturn(Optional.of(waitingLobby()));
        when(lobbyRepository.getCurrentPlayerCount(CODE)).thenReturn(2);
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(CODE))
                .thenReturn(Optional.of(gameLobbyWith(LobbyStatus.WAITING, 10L)));

        givenSelectedMapHasNumOfSong(10L, 12);
        givenRedisSettingsUpdateSucceeds();

        when(gameLobbyJpaRepository.saveAndFlush(any()))
                .thenThrow(new RuntimeException("DB 장애"));

        when(lobbyRepository.restoreSettings(
                CODE,
                OLD_MAX_PLAYERS,
                OLD_QUESTION_COUNT,
                OLD_TIME_LIMIT_SECONDS
        )).thenReturn(LobbySettingsRestoreResult.MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS);

        doThrow(new RuntimeException("설정 재처리 큐 장애"))
                .when(lobbySettingsReconciliationRepository)
                .enqueueSettingsReconciliation(
                        eq(CODE),
                        eq(SETTINGS_RESTORE_FAILED_REASON)
                );

        assertThatThrownBy(() -> sut.updateSettings(CODE, validRequest(), hostPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

        verify(lobbySettingsReconciliationRepository).enqueueSettingsReconciliation(
                eq(CODE),
                eq(SETTINGS_RESTORE_FAILED_REASON)
        );
        verify(lobbyRepository, never()).enqueueStartReconciliation(anyString(), anyString());
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(anyString());
    }
}