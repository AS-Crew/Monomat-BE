/*
 * 로비 대기실 설정 변경 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 인증 주체 검증 (userId / userIdentifier null 모두 차단)
 * - Redis 로비 1차 조회 (존재 여부 / WAITING 상태 / 방장 여부)
 * - 현재 참가자 수 기준 maxPlayers 하향 제한 검증
 * - 선택된 맵이 있는 경우 questionCount <= map.numOfSong 검증
 * - DB GAME_LOBBY 행에 대한 PESSIMISTIC_WRITE 락 획득 (게임 시작/맵 변경 경로와 직렬화)
 * - 락 획득 후 entity.status로 WAITING 재검증
 * - Redis 로비 설정값 갱신
 * - DB GAME_LOBBY 설정값 갱신
 * - DB 갱신 실패 시 Redis 설정값 보상 복구
 * - 참여자 refresh 이벤트 발행 (트랜잭션 커밋 후)
 *
 * [정합성 정책]
 * Redis가 로비 실시간 상태의 source of truth이므로 설정 변경도 Redis를 먼저 반영한다.
 * DB는 영속/감사 스냅샷 역할이며, DB 갱신 실패 시 Redis를 이전 설정값으로 복구한다.
 *
 * [동시성 정책]
 * 게임 시작, 맵 변경, 설정 변경은 모두 GAME_LOBBY row의 PESSIMISTIC_WRITE 락을 통해 직렬화한다.
 * findByInviteCodeForUpdate에는 3000ms lock timeout이 적용되어 있으므로,
 * 락 경합 시 409 CONFLICT로 변환한다.
 *
 * [주의]
 * Redis 보상은 이전 maxPlayers/questionCount/timeLimitSeconds를 복구한다.
 * 보상 시점에도 현재 트랜잭션이 DB row lock을 보유하고 있으므로,
 * 동일 row lock을 사용하는 게임 시작/맵 변경 경로와는 직렬화된다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbySettingsUpdateService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

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

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";
    private static final String LOG_DB_UPDATE_FAILED =
            "DB 로비 설정 갱신 실패 - Redis 설정 보상 복구 시작. code: {}";

    private static final String RECONCILIATION_REASON_SETTINGS_UPDATE_SNAPSHOT_NOT_FOUND =
            "SETTINGS_UPDATE_DB_SNAPSHOT_NOT_FOUND";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final QuizMapJpaRepository quizMapJpaRepository;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;

    /**
     * 로비 대기실에서 방장의 설정 변경 요청을 처리한다.
     *
     * [처리 순서]
     * 1. principal / userId / userIdentifier null 검증
     * 2. Redis 1차 조회 (존재 / WAITING / 방장 검증)
     * 3. 현재 참가자 수 기준 maxPlayers 검증
     * 4. DB GAME_LOBBY 행 PESSIMISTIC_WRITE 락 획득
     *    - row 없음 → handleMissingGameLobbySnapshot
     *    - status != WAITING → 409 NOT_WAITING
     * 5. 선택 맵이 있으면 questionCount <= map.numOfSong 검증
     * 6. Redis 설정 선갱신
     * 7. DB GAME_LOBBY 설정 갱신
     * 8. DB 실패 시 Redis 이전 설정값 보상 복구
     * 9. 트랜잭션 커밋 후 참여자 refresh 이벤트 발행
     *
     * @param code      로비 초대 코드
     * @param request   설정 변경 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     */
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

        lobbyRepository.updateSettings(
                code,
                request.maxPlayers(),
                request.questionCount(),
                request.timeLimitSeconds()
        );

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

    /**
     * 선택된 맵이 있으면 요청 questionCount가 해당 맵의 등록 곡 수를 초과하지 않는지 검증한다.
     *
     * [정책]
     * - mapId == null: 맵 미선택 로비이므로 DTO 범위 검증만 적용한다.
     * - mapId != null: DB의 최신 QuizMap.numOfSong 기준으로 상한을 검증한다.
     *
     * [맵 누락 처리]
     * 로비 Redis/DB에는 mapId가 있는데 QuizMap row가 없으면 정합성 이상 상태다.
     * 이 경우 설정 변경을 허용하면 이후 게임 시작 조건이 더 꼬일 수 있으므로 409로 차단한다.
     */
    private void validateQuestionCountWithSelectedMap(Long mapId, int questionCount) {
        if (mapId == null) {
            return;
        }

        QuizMap quizMap = quizMapJpaRepository.findById(mapId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        ERROR_SELECTED_MAP_NOT_FOUND
                ));

        if (questionCount > quizMap.getNumOfSong()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_QUESTION_COUNT_EXCEEDS_MAP_SONG_COUNT
            );
        }
    }

    /**
     * GAME_LOBBY 행에 대한 PESSIMISTIC_WRITE 락을 획득한다.
     *
     * [예외 처리]
     * - row 부재 → handleMissingGameLobbySnapshot 분기
     * - 락 획득 타임아웃 → 409 CONFLICT
     */
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

    /**
     * Redis 로비는 존재하지만 DB GAME_LOBBY 스냅샷이 없는 정합성 이상 상태를 처리한다.
     *
     * LobbyMapUpdateService와 동일한 패턴:
     * Redis 잔존 로비 보상 삭제를 시도하고, 실패하면 reconciliation 큐에 적재한다.
     */
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

    /**
     * DB 갱신 실패 시 Redis 설정값을 이전 값으로 복구한다.
     *
     * [정책]
     * Redis 설정값 갱신은 DB 갱신보다 먼저 수행된다.
     * DB saveAndFlush가 실패하면 클라이언트가 보는 Redis 상태가 DB 스냅샷과 어긋날 수 있으므로,
     * 이전 설정값으로 즉시 복구한다.
     *
     * [실패 처리]
     * Redis 복구 실패가 이미 발생한 DB 실패를 덮어쓰면 안 된다.
     * 따라서 복구 실패는 ERROR 로그만 남기고 원래의 500 응답을 유지한다.
     */
    private void compensateRedisSettings(
            String code,
            int oldMaxPlayers,
            int oldQuestionCount,
            int oldTimeLimitSeconds
    ) {
        try {
            lobbyRepository.updateSettings(
                    code,
                    oldMaxPlayers,
                    oldQuestionCount,
                    oldTimeLimitSeconds
            );

            log.info(
                    "Redis 로비 설정 보상 복구 완료 - code: {}, maxPlayers: {}, questionCount: {}, timeLimitSeconds: {}",
                    code,
                    oldMaxPlayers,
                    oldQuestionCount,
                    oldTimeLimitSeconds
            );
        } catch (Exception e) {
            log.error(
                    "{} Redis 로비 설정 보상 복구 실패 - Redis-DB 설정값 불일치 가능성 있음. "
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

    private void registerSettingsChangedEventAfterCommit(String code) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error(
                    "{} 로비 설정 변경 이벤트 afterCommit 등록 실패 - 트랜잭션 동기화 비활성. code: {}",
                    LOG_MONITORING_REQUIRED,
                    code
            );
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_UPDATE_SETTINGS_FAILED);
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lobbyRealtimeNotifier.notifyLobbyInfoRefresh(code);
            }
        });
    }
}