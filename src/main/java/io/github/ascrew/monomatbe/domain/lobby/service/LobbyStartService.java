/*
 * 로비 게임 시작 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 인증 주체 검증
 * - Redis 로비 존재 여부 확인
 * - DB GAME_LOBBY 스냅샷 조회
 * - 시작 전 맵/라운드 검증 정책 위임
 * - start_lobby.lua 실행을 통한 원자적 시작 조건 검증
 * - DB 로비 상태 WAITING -> PLAYING 전환
 * - DB 상태 변경 실패 시 Redis 상태 보상 롤백
 * - Redis 롤백 실패 시 reconciliation queue 적재
 * - DB commit 이후 GAME_STARTED / REFRESH_LOBBY_INFO 이벤트 발행
 *
 * [책임 경계]
 * 이 서비스는 로비를 PLAYING 상태로 전환하고 게임 시작 이벤트를 발행하는 것까지만 담당한다.
 * 실제 인게임 라운드 생성, 문제 송출, 정답 판별, 점수 계산은 별도 인게임 도메인에서 처리한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyStartService {

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_NOT_WAITING =
            "게임이 이미 시작된 로비에는 입장할 수 없습니다.";
    private static final String ERROR_START_FORBIDDEN =
            "방장만 게임을 시작할 수 있습니다.";
    private static final String ERROR_START_HOST_NOT_FOUND =
            "로비 방장 정보가 유효하지 않습니다.";
    private static final String ERROR_START_MAP_NOT_SELECTED =
            "게임을 시작하려면 맵을 선택해야 합니다.";
    private static final String ERROR_START_NO_PLAYER =
            "게임을 시작하려면 방장을 제외한 참여자가 1명 이상 필요합니다.";
    private static final String ERROR_START_NOT_READY =
            "모든 참여자가 준비 완료 상태여야 합니다.";
    private static final String ERROR_START_FAILED =
            "게임 시작 처리에 실패했습니다.";
    private static final String ERROR_START_DB_SYNC_FAILED =
            "게임 시작 상태 동기화에 실패했습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_START_EVENT_TRANSACTION_REQUIRED =
            "게임 시작 이벤트 발행을 위한 트랜잭션 동기화가 활성화되어 있지 않습니다.";
    private static final String ERROR_LOBBY_SNAPSHOT_NOT_FOUND =
            "로비 상태 정보가 일치하지 않습니다. 로비를 다시 생성해주세요.";
    private static final String ERROR_LOBBY_LOCK_CONTENTION =
            "다른 로비 상태 변경이 진행 중입니다. 잠시 후 다시 시도해주세요.";

    private static final String RECONCILIATION_REASON_DB_SYNC_FAILED =
            "START_DB_SYNC_FAILED";
    private static final String RECONCILIATION_REASON_DB_SNAPSHOT_NOT_FOUND =
            "START_DB_SNAPSHOT_NOT_FOUND";

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";

    private final LobbyRepository lobbyRepository;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final LobbyStartPolicy lobbyStartPolicy;
    private final io.github.ascrew.monomatbe.domain.game.service.GameSessionCreateService gameSessionCreateService;
    private final io.github.ascrew.monomatbe.domain.game.service.GameRealtimeNotifier gameRealtimeNotifier;

    /**
     * 로비 게임 시작 요청을 처리한다.
     *
     * [검증 순서]
     * 1. 인증 정보 확인
     * 2. Redis 로비 존재 여부 확인
     * 3. DB 로비 스냅샷 조회
     * 4. LobbyStartPolicy로 선택 맵/라운드 검증
     * 5. Redis Lua가 상태를 변경하기 전 트랜잭션 동기화 활성 여부 확인
     * 6. start_lobby.lua로 방장 권한, WAITING 상태, ready 상태를 원자 검증
     * 7. DB 로비 상태를 PLAYING으로 변경
     * 8. DB commit 이후 GAME_STARTED / REFRESH_LOBBY_INFO 이벤트 발행
     */
    @Transactional
    public void startLobbyGame(String code, CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            log.warn("게임 시작 요청 거부 - principal 또는 userId가 null. code: {}", code);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        String requesterIdentifier = principal.userIdentifier();

        if (lobbyRepository.findByInviteCode(code).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );
        }

        GameLobby gameLobby = acquireGameLobbyRowLock(code, requesterIdentifier);

        QuizMap quizMap = lobbyStartPolicy.validateStartableMap(gameLobby);

        validateTransactionSynchronizationForGameStart(code, requesterIdentifier);

        StartLobbyResult result = lobbyRepository.executeStartLobbyProcess(
                code,
                requesterIdentifier
        );

        handleStartLobbyResult(result);

        io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto firstRound;
        try {
            gameLobby.changeStatus(LobbyStatus.PLAYING);
            gameLobbyJpaRepository.saveAndFlush(gameLobby);
            firstRound = gameSessionCreateService.createGameSession(gameLobby);
        } catch (Exception e) {
            log.error(
                    "게임 시작 DB 상태 변경 실패 - Redis 상태 보상 롤백 시도. code: {}, requester: {}",
                    code,
                    requesterIdentifier,
                    e
            );

            boolean rollbackSucceeded = lobbyRepository.rollbackStartedLobbyStatus(code);

            if (!rollbackSucceeded) {
                lobbyRepository.enqueueStartReconciliation(
                        code,
                        RECONCILIATION_REASON_DB_SYNC_FAILED
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_START_DB_SYNC_FAILED
            );
        }

        registerGameStartedEventAfterCommit(code, requesterIdentifier, firstRound);

        log.info(
                "게임 시작 처리 완료 - code: {}, requester: {}, mapId: {}, roundCount: {}",
                code,
                requesterIdentifier,
                quizMap.getId(),
                gameLobby.getRoundCount()
        );
    }

    /**
     * GAME_LOBBY 행에 대한 PESSIMISTIC_WRITE 락을 획득한다.
     *
     * [예외 처리]
     * - row 부재 → handleMissingGameLobbySnapshot 분기 (Redis 보상 삭제 + reconciliation)
     * - 락 획득 타임아웃(3초) → PessimisticLockingFailureException을 잡아 409 CONFLICT로 변환.
     *   동시 맵 변경(LobbyMapUpdateService)이 진행 중일 때 명확한 신호를 클라이언트에게 준다.
     */
    private GameLobby acquireGameLobbyRowLock(String code, String requesterIdentifier) {
        try {
            return gameLobbyJpaRepository.findByInviteCodeForUpdate(code)
                    .orElseGet(() -> handleMissingGameLobbySnapshot(code, requesterIdentifier));
        } catch (PessimisticLockingFailureException e) {
            log.warn(
                    "게임 시작 락 획득 타임아웃 - code: {}, requester: {}",
                    code,
                    requesterIdentifier,
                    e
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_LOBBY_LOCK_CONTENTION);
        }
    }

    private GameLobby handleMissingGameLobbySnapshot(
            String code,
            String requesterIdentifier
    ) {
        log.error(
                "{} Redis 로비는 존재하지만 DB GAME_LOBBY 스냅샷이 없습니다. "
                        + "Redis 잔존 로비 보상 삭제를 시도합니다. code: {}, requester: {}",
                LOG_ALERT_REQUIRED,
                code,
                requesterIdentifier
        );

        boolean deleted = lobbyRepository.deleteFromRedis(code);

        if (!deleted) {
            lobbyRepository.enqueueStartReconciliation(
                    code,
                    RECONCILIATION_REASON_DB_SNAPSHOT_NOT_FOUND
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

    private void validateTransactionSynchronizationForGameStart(
            String code,
            String requesterIdentifier
    ) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        log.error(
                "{} 게임 시작 요청 거부 - 트랜잭션 동기화 비활성. "
                        + "Redis 상태 변경 전에 차단합니다. code: {}, requester: {}",
                LOG_ALERT_REQUIRED,
                code,
                requesterIdentifier
        );

        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ERROR_START_EVENT_TRANSACTION_REQUIRED
        );
    }

    /**
     * 게임 시작 이벤트를 DB 트랜잭션 커밋 이후에 발행한다.
     *
     * [Issue #81 변경]
     * 직접 STOMP 발행을 수행하지 않고 LobbyRealtimeNotifier에 위임한다.
     */
    private void registerGameStartedEventAfterCommit(
            String code,
            String requesterIdentifier,
            io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto firstRound
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error(
                    "{} 게임 시작 이벤트 afterCommit 등록 실패 - 트랜잭션 동기화 비활성. "
                            + "code: {}, requester: {}",
                    LOG_ALERT_REQUIRED,
                    code,
                    requesterIdentifier
            );
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_START_EVENT_TRANSACTION_REQUIRED
            );
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    lobbyRealtimeNotifier.notifyGameStarted(code);
                } catch (Exception e) {
                    log.error("[ALERT_REQUIRED] GAME_STARTED 발행 실패 - code: {}, requester: {}", code, requesterIdentifier, e);
                }

                try {
                    lobbyRealtimeNotifier.notifyLobbyInfoRefresh(code, requesterIdentifier);
                } catch (Exception e) {
                    log.error("[ALERT_REQUIRED] REFRESH_LOBBY_INFO 발행 실패 - code: {}, requester: {}", code, requesterIdentifier, e);
                }

                try {
                    gameRealtimeNotifier.notifyRoundStart(code, firstRound);
                } catch (Exception e) {
                    log.error("[ALERT_REQUIRED] ROUND_START 발행 실패 - code: {}, requester: {}, roundNo: {}", code, requesterIdentifier, firstRound.roundNo(), e);
                }
            }
        });
    }

    private void handleStartLobbyResult(StartLobbyResult result) {
        switch (result) {
            case StartLobbyResult.Started ignored -> {
                return;
            }

            case StartLobbyResult.LobbyNotFound ignored -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );

            case StartLobbyResult.HostNotFound ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_HOST_NOT_FOUND
            );

            case StartLobbyResult.Forbidden ignored -> throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_START_FORBIDDEN
            );

            case StartLobbyResult.LobbyNotWaiting ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_LOBBY_NOT_WAITING
            );

            case StartLobbyResult.MapNotSelected ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_MAP_NOT_SELECTED
            );

            case StartLobbyResult.NoPlayer ignored -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_START_NO_PLAYER
            );

            case StartLobbyResult.NotReady notReady -> {
                log.warn(
                        "게임 시작 요청 거부 - 준비하지 않은 참여자 존재. code: {}, userIdentifier: {}",
                        notReady.lobbyCode(),
                        notReady.userIdentifier()
                );
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ERROR_START_NOT_READY
                );
            }

            case StartLobbyResult.Error error -> {
                log.error("게임 시작 처리 실패 - reason: {}", error.reason());
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ERROR_START_FAILED
                );
            }

            case StartLobbyResult.StaleParticipant staleParticipant -> {
                log.warn(
                        "게임 시작 요청 거부 - stale 참여자 감지. code: {}, userIdentifier: {}",
                        staleParticipant.lobbyCode(),
                        staleParticipant.userIdentifier()
                );
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ERROR_START_NOT_READY
                );
            }
        }
    }
}