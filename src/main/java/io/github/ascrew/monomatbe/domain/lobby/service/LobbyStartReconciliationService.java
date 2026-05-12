package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 게임 시작 상태 동기화 실패 재처리 서비스
 *
 * [처리 대상]
 * Redis start_lobby.lua는 성공했지만 DB GAME_LOBBY 상태 변경 또는
 * Redis 보상 롤백이 실패하여 Redis-DB 상태가 불일치할 가능성이 있는 로비를 보정한다.
 *
 * [재시도 정책]
 * - payload에 attempt와 nextRetryAtEpochMillis를 포함한다.
 * - 아직 재시도 시각이 아니면 다시 큐 뒤에 넣는다.
 * - 실패 시 exponential backoff로 재시도 간격을 늘힌다.
 * - 최대 재시도 횟수를 초과하면 재적재하지 않고 ALERT 로그를 남긴다.
 *
 * [payload 형식]
 * lobbyCode|reason|attempt|nextRetryAtEpochMillis
 */
@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class LobbyStartReconciliationService {

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";

    private static final String PAYLOAD_DELIMITER_REGEX = "\\|";
    private static final String PAYLOAD_DELIMITER = "|";

    private static final int EXPECTED_PAYLOAD_PARTS = 4;

    private static final long BASE_BACKOFF_MS = 30_000L;
    private static final long MAX_BACKOFF_MS = 5 * 60_000L;
    private static final int MAX_RETRY_ATTEMPT = 5;

    private final LobbyRepository lobbyRepository;

    /**
     * Redis-DB 게임 시작 상태 불일치 재처리 큐를 주기적으로 처리한다.
     *
     * [동작]
     * 1. Redis 큐에서 payload를 하나 꺼낸다.
     * 2. payload 형식이 잘못되었으면 실패 metric을 증가시키고 종료한다.
     * 3. 아직 nextRetryAtEpochMillis에 도달하지 않았으면 다시 큐에 넣는다.
     * 4. 재처리 대상 로비의 Redis 상태를 WAITING으로 롤백한다.
     * 5. 실패 시 backoff 정보를 갱신해 다시 큐에 넣는다.
     */
    @Scheduled(fixedDelayString = "${monomat.lobby.start-reconciliation.fixed-delay-ms:30000}")
    public void reconcileStartState() {
        String payload = lobbyRepository.pollStartReconciliation();

        if (!StringUtils.hasText(payload)) {
            return;
        }

        ReconciliationPayload parsedPayload = parsePayload(payload);

        if (parsedPayload == null) {
            log.error(
                    "{} 게임 시작 상태 재처리 payload 파싱 실패 - payload: {}",
                    LOG_ALERT_REQUIRED,
                    payload
            );
            lobbyRepository.incrementStartReconciliationMetric(
                    RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED
            );
            return;
        }

        long now = System.currentTimeMillis();

        if (parsedPayload.nextRetryAtEpochMillis() > now) {
            lobbyRepository.requeueStartReconciliation(payload);
            return;
        }

        boolean reconciled = lobbyRepository.rollbackStartedLobbyStatus(parsedPayload.lobbyCode());

        if (reconciled) {
            lobbyRepository.incrementStartReconciliationMetric(
                    RedisKeys.METRIC_LOBBY_START_RECONCILIATION_SUCCESS
            );

            log.warn(
                    "게임 시작 상태 재처리 완료 - lobbyCode: {}, reason: {}, attempt: {}",
                    parsedPayload.lobbyCode(),
                    parsedPayload.reason(),
                    parsedPayload.attempt()
            );
            return;
        }

        lobbyRepository.incrementStartReconciliationMetric(
                RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED
        );

        if (parsedPayload.attempt() >= MAX_RETRY_ATTEMPT) {
            log.error(
                    "{} 게임 시작 상태 재처리 최대 횟수 초과 - lobbyCode: {}, reason: {}, attempt: {}. "
                            + "수동 정합성 확인이 필요합니다.",
                    LOG_ALERT_REQUIRED,
                    parsedPayload.lobbyCode(),
                    parsedPayload.reason(),
                    parsedPayload.attempt()
            );
            return;
        }

        String retryPayload = parsedPayload.nextRetryPayload();

        lobbyRepository.requeueStartReconciliation(retryPayload);

        log.error(
                "{} 게임 시작 상태 재처리 실패 - backoff 후 재시도 예정. "
                        + "lobbyCode: {}, reason: {}, nextPayload: {}",
                LOG_ALERT_REQUIRED,
                parsedPayload.lobbyCode(),
                parsedPayload.reason(),
                retryPayload
        );
    }

    /**
     * Redis 큐 payload를 파싱
     *
     * payload 형식:
     * lobbyCode|reason|attempt|nextRetryAtEpochMillis
     *
     * @param payload Redis 큐에서 꺼낸 원본 payload
     * @return 파싱 성공 시 ReconciliationPayload, 실패 시 null
     */
    private ReconciliationPayload parsePayload(String payload) {
        String[] parts = payload.split(PAYLOAD_DELIMITER_REGEX, EXPECTED_PAYLOAD_PARTS);

        if (parts.length != EXPECTED_PAYLOAD_PARTS
                || !StringUtils.hasText(parts[0])
                || !StringUtils.hasText(parts[1])
                || !StringUtils.hasText(parts[2])
                || !StringUtils.hasText(parts[3])) {
            return null;
        }

        try {
            return new ReconciliationPayload(
                    parts[0],
                    parts[1],
                    Integer.parseInt(parts[2]),
                    Long.parseLong(parts[3])
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 게임 시작 상태 재처리 payload.
     *
     * @param lobbyCode 로비 초대 코드
     * @param reason 재처리 사유
     * @param attempt 현재 재시도 횟수
     * @param nextRetryAtEpochMillis 다음 재시도 가능 시각
     */
    private record ReconciliationPayload(
            String lobbyCode,
            String reason,
            int attempt,
            long nextRetryAtEpochMillis
    ) {

        /**
         * 다음 재시도를 위한 payload를 생성한다.
         *
         * [backoff 계산]
         * - attempt가 증가할수록 재시도 간격을 늘린다.
         * - 최대 backoff는 MAX_BACKOFF_MS로 제한한다.
         *
         * @return 다음 재시도 payload
         */
        String nextRetryPayload() {
            int nextAttempt = attempt + 1;
            long backoffMs = Math.min(
                    BASE_BACKOFF_MS * (1L << Math.min(nextAttempt, 4)),
                    MAX_BACKOFF_MS
            );
            long nextRetryAt = System.currentTimeMillis() + backoffMs;

            return String.join(
                    PAYLOAD_DELIMITER,
                    lobbyCode,
                    reason,
                    String.valueOf(nextAttempt),
                    String.valueOf(nextRetryAt)
            );
        }
    }
}