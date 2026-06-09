package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LobbySettingsRestoreResult;
import io.github.ascrew.monomatbe.domain.lobby.LobbySettingsUpdateResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.UpdateLobbySettingsRequest;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * 로비 대기실 설정 변경 유스케이스를 담당하는 서비스.
 *
 * [책임]
 * - 인증 주체 검증
 * - Redis 로비 1차 조회
 * - 방장 검증
 * - WAITING 상태 검증
 * - 현재 참가자 수 기준 maxPlayers 검증
 * - 선택된 맵 기준 questionCount 검증
 * - DB GAME_LOBBY row lock 획득
 * - Redis 설정 변경 Lua 실행
 * - DB GAME_LOBBY 스냅샷 갱신
 * - DB 실패 시 Redis 설정값 보상 복구
 * - 커밋 후 로비 refresh 이벤트 발행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbySettingsUpdateService {

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에서는 설정을 변경할 수 없습니다.";
    private static final String ERROR_NOT_HOST =
            "방장만 로비 설정을 변경할 수 있습니다.";
    private static final String ERROR_MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS =
            "최대 인원은 현재 참가자 수보다 작을 수 없습니다.";
    private static final String ERROR_QUESTION_COUNT_EXCEEDS_MAP_SONG_COUNT =
            "문제 수는 선택된 맵의 등록 곡 수를 초과할 수 없습니다.";
    private static final String ERROR_SELECTED_MAP_NOT_FOUND =
            "선택된 맵 정보를 찾을 수 없습니다. 맵을 다시 선택해주세요.";
    private static final String ERROR_UPDATE_SETTINGS_FAILED =
            "로비 설정 변경에 실패했습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_LOBBY_SNAPSHOT_NOT_FOUND =
            "로비 상태 정보가 일치하지 않습니다. 로비를 다시 생성해주세요.";
    private static final String ERROR_LOBBY_LOCK_CONTENTION =
            "다른 로비 상태 변경이 진행 중입니다. 잠시 후 다시 시도해주세요.";

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";
    private static final String LOG_DB_UPDATE_FAILED =
            "DB 로비 설정 갱신 실패 - Redis 설정 보상 복구 시작. code: {}";

    private static final String RECONCILIATION_REASON_SETTINGS_UPDATE_SNAPSHOT_NOT_FOUND =
            "SETTINGS_UPDATE_DB_SNAPSHOT_NOT_FOUND";

    private static final String RECONCILIATION_REASON_SETTINGS_RESTORE_FAILED =
            "SETTINGS_UPDATE_RESTORE_FAILED";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final QuizMapJpaRepository quizMapJpaRepository;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;

    @Transactional
    public void updateSettings(
            String code,
            UpdateLobbySettingsRequest request,
            CustomPrincipal principal
    ) {
        validatePrincipal(code, principal);

        JoinLobbyResponse lobbyInfo = lobbyRepository.findByInviteCode(code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        validateWaitingLobby(lobbyInfo.status());
        validateHost(principal, lobbyInfo);

        int currentPlayerCount = lobbyRepository.getCurrentPlayerCount(code);
        validateMaxPlayers(request.maxPlayers(), currentPlayerCount);

        GameLobby gameLobby = acquireGameLobbyRowLock(code, principal.userIdentifier());

        if (gameLobby.getStatus() != LobbyStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }

        validateQuestionCountWithSelectedMap(gameLobby.getMapId(), request.questionCount());

        int oldMaxPlayers = gameLobby.getMaxPlayers();
        int oldQuestionCount = gameLobby.getQuestionCount();
        int oldTimeLimitSeconds = gameLobby.getTimeLimitSeconds();

        LobbySettingsUpdateResult redisUpdateResult = lobbyRepository.updateSettings(
                code,
                request.maxPlayers(),
                request.questionCount(),
                request.timeLimitSeconds()
        );

        handleRedisSettingsUpdateResult(redisUpdateResult);

        try {
            gameLobby.updateSettings(
                    request.maxPlayers(),
                    request.questionCount(),
                    request.timeLimitSeconds()
            );
            gameLobbyJpaRepository.saveAndFlush(gameLobby);
        } catch (Exception e) {
            log.error(LOG_DB_UPDATE_FAILED, code, e);

            compensateRedisSettings(
                    code,
                    oldMaxPlayers,
                    oldQuestionCount,
                    oldTimeLimitSeconds
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_UPDATE_SETTINGS_FAILED
            );
        }

        log.info(
                "로비 설정 변경 완료 - code: {}, host: {}, maxPlayers: {} -> {}, "
                        + "questionCount: {} -> {}, timeLimitSeconds: {} -> {}",
                code,
                principal.userIdentifier(),
                oldMaxPlayers,
                request.maxPlayers(),
                oldQuestionCount,
                request.questionCount(),
                oldTimeLimitSeconds,
                request.timeLimitSeconds()
        );

        registerSettingsChangedEventAfterCommit(code);
    }

    private void validatePrincipal(String code, CustomPrincipal principal) {
        if (principal == null
                || principal.userId() == null
                || principal.userIdentifier() == null) {
            log.warn(
                    "로비 설정 변경 요청 거부 - principal/userId/userIdentifier null. code: {}",
                    code
            );
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }
    }

    private void validateWaitingLobby(String status) {
        if (!LobbyStatus.WAITING.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_NOT_WAITING);
        }
    }

    private void validateHost(CustomPrincipal principal, JoinLobbyResponse lobbyInfo) {
        if (!Objects.equals(principal.userIdentifier(), lobbyInfo.hostId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_NOT_HOST);
        }
    }

    private void validateMaxPlayers(int maxPlayers, int currentPlayerCount) {
        if (maxPlayers < currentPlayerCount) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS
            );
        }
    }

    private void validateQuestionCountWithSelectedMap(Long mapId, int questionCount) {
        if (mapId == null) {
            return;
        }

        QuizMap quizMap = quizMapJpaRepository.findById(mapId)
                .filter(map -> !Boolean.TRUE.equals(map.getIsDeleted()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ERROR_SELECTED_MAP_NOT_FOUND
                ));

        Integer numOfSong = quizMap.getNumOfSong();

        if (numOfSong == null || questionCount > numOfSong) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_QUESTION_COUNT_EXCEEDS_MAP_SONG_COUNT
            );
        }
    }

    private GameLobby acquireGameLobbyRowLock(String code, String requesterIdentifier) {
        try {
            return gameLobbyJpaRepository.findByInviteCodeForUpdate(code)
                    .orElseGet(() -> handleMissingGameLobbySnapshot(code, requesterIdentifier));
        } catch (PessimisticLockingFailureException e) {
            log.warn(
                    "로비 설정 변경 락 획득 타임아웃 - code: {}, requester: {}",
                    code,
                    requesterIdentifier,
                    e
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_LOCK_CONTENTION);
        }
    }

    private GameLobby handleMissingGameLobbySnapshot(String code, String requesterIdentifier) {
        log.error(
                "{} Redis 로비는 존재하지만 DB GAME_LOBBY 스냅샷이 없습니다. "
                        + "설정 변경 경로에서 감지 - Redis 잔존 로비 보상 삭제를 시도합니다. code: {}, requester: {}",
                LOG_ALERT_REQUIRED,
                code,
                requesterIdentifier
        );

        boolean deleted = lobbyRepository.deleteFromRedis(code);

        if (!deleted) {
            lobbyRepository.enqueueStartReconciliation(
                    code,
                    RECONCILIATION_REASON_SETTINGS_UPDATE_SNAPSHOT_NOT_FOUND
            );

            log.error(
                    "{} DB 스냅샷 누락 로비 Redis 삭제 실패 - 재처리 큐 적재 완료. code: {}, requester: {}",
                    LOG_ALERT_REQUIRED,
                    code,
                    requesterIdentifier
            );
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                ERROR_LOBBY_SNAPSHOT_NOT_FOUND
        );
    }

    private void handleRedisSettingsUpdateResult(LobbySettingsUpdateResult result) {
        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_UPDATE_SETTINGS_FAILED
            );
        }

        switch (result) {
            case UPDATED -> {
                return;
            }
            case LOBBY_NOT_FOUND -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );
            case NOT_WAITING -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_LOBBY_NOT_WAITING
            );
            case MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS
            );
            case ERROR -> throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_UPDATE_SETTINGS_FAILED
            );
        }
    }

    private void compensateRedisSettings(
            String code,
            int oldMaxPlayers,
            int oldQuestionCount,
            int oldTimeLimitSeconds
    ) {
        try {
            LobbySettingsRestoreResult restoreResult = lobbyRepository.restoreSettings(
                    code,
                    oldMaxPlayers,
                    oldQuestionCount,
                    oldTimeLimitSeconds
            );

            if (restoreResult == LobbySettingsRestoreResult.RESTORED) {
                log.info(
                        "Redis 로비 설정 보상 복구 완료 - code: {}, maxPlayers: {}, questionCount: {}, timeLimitSeconds: {}",
                        code,
                        oldMaxPlayers,
                        oldQuestionCount,
                        oldTimeLimitSeconds
                );
                return;
            }

            String reason = RECONCILIATION_REASON_SETTINGS_RESTORE_FAILED + ":" + restoreResult.name();
            safeEnqueueSettingsRestoreReconciliation(code, reason);

            log.error(
                    "{} Redis 로비 설정 보상 복구 미완료 - 재처리 큐 적재 시도. "
                            + "code: {}, result: {}, oldMaxPlayers: {}, oldQuestionCount: {}, oldTimeLimitSeconds: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    restoreResult,
                    oldMaxPlayers,
                    oldQuestionCount,
                    oldTimeLimitSeconds
            );

        } catch (Exception e) {
            String reason = RECONCILIATION_REASON_SETTINGS_RESTORE_FAILED + ":EXCEPTION";
            safeEnqueueSettingsRestoreReconciliation(code, reason);

            log.error(
                    "{} Redis 로비 설정 보상 복구 실패 - 재처리 큐 적재 시도. "
                            + "code: {}, oldMaxPlayers: {}, oldQuestionCount: {}, oldTimeLimitSeconds: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    oldMaxPlayers,
                    oldQuestionCount,
                    oldTimeLimitSeconds,
                    e
            );
        }
    }

    private void safeEnqueueSettingsRestoreReconciliation(String code, String reason) {
        try {
            lobbyRepository.enqueueStartReconciliation(code, reason);
        } catch (Exception e) {
            log.error(
                    "{} 로비 설정 복구 실패 재처리 큐 적재 실패 - code: {}, reason: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    reason,
                    e
            );
        }
    }

    private void registerSettingsChangedEventAfterCommit(String code) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error(
                    "{} 로비 설정 변경 이벤트 afterCommit 등록 실패 - 트랜잭션 동기화 비활성. code: {}",
                    LOG_MONITORING_REQUIRED,
                    code
            );
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_UPDATE_SETTINGS_FAILED
            );
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lobbyRealtimeNotifier.notifyLobbyInfoRefresh(code);
            }
        });
    }
}