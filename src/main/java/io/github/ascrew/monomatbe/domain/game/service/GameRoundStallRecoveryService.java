package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.repository.redis.GameRoundRecoveryRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 다음 라운드 진행 정지 복구 서비스. (상시 동작 큐 워커)
 *
 * [처리 대상]
 * 다음 라운드 시작은 인메모리 TaskScheduler에 의존하므로, 인스턴스 재시작/afterCommit 중단으로
 * 예약 태스크가 유실되면 게임이 특정 라운드에서 영구 정지될 수 있다. scheduleNextRoundWithDelay가
 * 적재한 durable 복구 큐를 점검해, 예약 시각이 지났는데도 진행되지 않은 라운드만 재트리거한다.
 *
 * 부팅 직후 1회 활성 세션을 스캔해 phase별 타이머를 재구성하는 복구는
 * {@link GameRoundRecoveryService}가 담당한다. (보완적 관계)
 *
 * [멱등성]
 * 재트리거는 GameRoundNextRoundExecutor.startNextRound의 SETNX 멱등 락으로 보호되므로,
 * 인메모리 예약과 복구가 동시에 동작해도 라운드가 중복 진행/스킵되지 않는다.
 *
 * [재시도 정책]
 * payload에 attempt/nextRetryAtEpochMillis를 포함한다. 아직 점검 시각이 아니면 다시 큐에 넣고,
 * 실패 시 exponential backoff로 간격을 늘리며 최대 횟수 초과 시 ALERT 로그를 남긴다.
 *
 * [payload 형식]
 * lobbyCode|expectedRoundNo|attempt|nextRetryAtEpochMillis
 *
 * LobbyStartReconciliationService와 동일한 큐/backoff/metric 패턴을 따른다.
 */
@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class GameRoundStallRecoveryService {

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";

    private static final String PAYLOAD_DELIMITER_REGEX = "\\|";
    private static final String PAYLOAD_DELIMITER = "|";
    private static final int EXPECTED_PAYLOAD_PARTS = 4;

    private static final long BASE_BACKOFF_MS = 30_000L;
    private static final long MAX_BACKOFF_MS = 5 * 60_000L;
    private static final int MAX_RETRY_ATTEMPT = 5;

    private static final String STATUS_FINISHED = "FINISHED";

    private final GameRoundRecoveryRepository gameRoundRecoveryRepository;
    private final GameRoundNextRoundExecutor gameRoundNextRoundExecutor;
    private final StringRedisTemplate redisTemplate;

    /**
     * 다음 라운드 진행 정지 복구 큐를 주기적으로 처리한다.
     */
    @Scheduled(fixedDelayString = "${monomat.game.round-recovery.fixed-delay-ms:30000}")
    public void recoverStalledRounds() {
        String payload = gameRoundRecoveryRepository.pollRoundRecovery();

        if (!StringUtils.hasText(payload)) {
            return;
        }

        // payload는 이미 leftPop으로 큐에서 제거되었다. 처리 중 어떤 예외가 나도 유실되지 않도록
        // 최상위에서 보상한다: 실패 metric을 남기고 원본 payload를 안전하게 다시 큐에 넣는다.
        try {
            processStalledRound(payload);
        } catch (Exception e) {
            log.error("{} 다음 라운드 복구 처리 중 예기치 못한 예외 - 원본 payload 안전 재적재. payload: {}",
                    LOG_ALERT_REQUIRED, payload, e);
            gameRoundRecoveryRepository.incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
            gameRoundRecoveryRepository.safeRequeueRoundRecovery(payload);
        }
    }

    private void processStalledRound(String payload) {
        RecoveryPayload parsed = parsePayload(payload);
        if (parsed == null) {
            log.error("{} 다음 라운드 복구 payload 파싱 실패 - payload: {}", LOG_ALERT_REQUIRED, payload);
            gameRoundRecoveryRepository.incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
            return;
        }

        long now = System.currentTimeMillis();
        if (parsed.nextRetryAtEpochMillis() > now) {
            // 아직 점검 시각이 아니다(예약 지연 + grace 미경과). 큐 뒤로 보낸다.
            gameRoundRecoveryRepository.requeueRoundRecovery(payload);
            return;
        }

        RoundProgressState state = inspectProgress(parsed.lobbyCode(), parsed.expectedRoundNo());

        if (state != RoundProgressState.STALLED) {
            // 이미 진행됐거나(세션 정상 진행/종료) 세션이 사라짐 → 복구 불필요. 성공으로 집계하고 드롭한다.
            gameRoundRecoveryRepository.incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_SUCCESS);
            log.info("다음 라운드 복구 불필요({}) - code: {}, expectedRoundNo: {}",
                    state, parsed.lobbyCode(), parsed.expectedRoundNo());
            return;
        }

        if (retriggerStartNextRound(parsed.lobbyCode(), parsed.expectedRoundNo())) {
            gameRoundRecoveryRepository.incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_SUCCESS);
            log.warn("정지된 다음 라운드 복구 재트리거 완료 - code: {}, expectedRoundNo: {}, attempt: {}",
                    parsed.lobbyCode(), parsed.expectedRoundNo(), parsed.attempt());
            return;
        }

        gameRoundRecoveryRepository.incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);

        if (parsed.attempt() >= MAX_RETRY_ATTEMPT) {
            log.error("{} 다음 라운드 복구 최대 횟수 초과 - code: {}, expectedRoundNo: {}, attempt: {}. "
                            + "수동 확인이 필요합니다.",
                    LOG_ALERT_REQUIRED, parsed.lobbyCode(), parsed.expectedRoundNo(), parsed.attempt());
            return;
        }

        gameRoundRecoveryRepository.requeueRoundRecovery(parsed.nextRetryPayload());
    }

    /**
     * 세션 진행 상태를 점검한다.
     *
     * <ul>
     *   <li>세션 해시가 없으면(SESSION_GONE): 정리/만료됨 → 복구 불필요</li>
     *   <li>status가 FINISHED이거나 current_round_no >= expectedRoundNo(ALREADY_PROGRESSED): 이미 진행됨</li>
     *   <li>그 외(STALLED): 예약이 유실되어 정지된 상태 → 재트리거 대상</li>
     * </ul>
     */
    private RoundProgressState inspectProgress(String lobbyCode, int expectedRoundNo) {
        try {
            String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
            // current_round_no와 status를 단일 RTT(HMGET)로 함께 조회한다.
            List<Object> fields = redisTemplate.opsForHash().multiGet(
                    sessionKey, List.of(RedisKeys.FIELD_CURRENT_ROUND_NO, RedisKeys.FIELD_STATUS));
            String currentRoundNoStr = (String) fields.get(0);

            if (currentRoundNoStr == null) {
                return RoundProgressState.SESSION_GONE;
            }

            String status = (String) fields.get(1);
            if (STATUS_FINISHED.equals(status)) {
                return RoundProgressState.ALREADY_PROGRESSED;
            }

            int currentRoundNo = Integer.parseInt(currentRoundNoStr.trim());
            return currentRoundNo >= expectedRoundNo
                    ? RoundProgressState.ALREADY_PROGRESSED
                    : RoundProgressState.STALLED;
        } catch (Exception e) {
            // 점검 자체 실패 시 STALLED로 보수적으로 처리해 재시도/백오프 경로를 타게 한다.
            log.warn("다음 라운드 진행 상태 점검 실패 - code: {}, expectedRoundNo: {}", lobbyCode, expectedRoundNo, e);
            return RoundProgressState.STALLED;
        }
    }

    /**
     * startNextRound를 재트리거한다. (멱등 락으로 중복 진행 방지)
     *
     * @return 예외 없이 처리되면 true, 예외 발생 시 false
     */
    private boolean retriggerStartNextRound(String lobbyCode, int expectedRoundNo) {
        try {
            gameRoundNextRoundExecutor.startNextRound(lobbyCode, expectedRoundNo);
            return true;
        } catch (Exception e) {
            log.error("정지된 다음 라운드 복구 재트리거 실패 - code: {}, expectedRoundNo: {}", lobbyCode, expectedRoundNo, e);
            return false;
        }
    }

    private RecoveryPayload parsePayload(String payload) {
        String[] parts = payload.split(PAYLOAD_DELIMITER_REGEX, EXPECTED_PAYLOAD_PARTS);

        if (parts.length != EXPECTED_PAYLOAD_PARTS
                || !StringUtils.hasText(parts[0])
                || !StringUtils.hasText(parts[1])
                || !StringUtils.hasText(parts[2])
                || !StringUtils.hasText(parts[3])) {
            return null;
        }

        try {
            return new RecoveryPayload(
                    parts[0],
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Long.parseLong(parts[3])
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private enum RoundProgressState {
        STALLED,
        ALREADY_PROGRESSED,
        SESSION_GONE
    }

    /**
     * 다음 라운드 진행 복구 payload.
     *
     * @param lobbyCode              로비 초대 코드
     * @param expectedRoundNo        진행되어야 하는 다음 라운드 번호
     * @param attempt                현재 재시도 횟수
     * @param nextRetryAtEpochMillis 다음 점검 가능 시각
     */
    private record RecoveryPayload(
            String lobbyCode,
            int expectedRoundNo,
            int attempt,
            long nextRetryAtEpochMillis
    ) {

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
                    String.valueOf(expectedRoundNo),
                    String.valueOf(nextAttempt),
                    String.valueOf(nextRetryAt)
            );
        }
    }
}
