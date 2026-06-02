package io.github.ascrew.monomatbe.domain.game.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 다음 라운드 진행 정지 복구 재처리 queue 전용 Repository.
 *
 * [사용 목적]
 * 다음 라운드 시작은 인메모리 TaskScheduler에 의존하므로 인스턴스 재시작/afterCommit 중단 시
 * 영구 정지될 수 있다. scheduleNextRound 시 durable 복구 요청을 Redis queue에 적재해 두고,
 * 복구 워커가 실제 라운드 진행 여부를 확인해 미진행분만 재트리거한다.
 *
 * [payload 형식]
 * lobbyCode|expectedRoundNo|attempt|nextRetryAtEpochMillis
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GameRoundRecoveryRepository {

    private static final String PAYLOAD_DELIMITER = "|";
    private static final String INITIAL_ATTEMPT = "0";

    private final StringRedisTemplate redisTemplate;

    /**
     * 다음 라운드 진행 복구 요청을 queue에 적재한다.
     *
     * 적재 실패는 복구 누락(라운드 영구 정지 가능)으로 이어질 수 있으므로 로그/실패 metric을 남기되,
     * 이 메서드 자체는 예외를 전파하지 않는다. (afterCommit 후처리 흐름을 깨지 않기 위함)
     *
     * @param lobbyCode              로비 초대 코드
     * @param expectedRoundNo        진행되어야 하는 다음 라운드 번호
     * @param nextRetryAtEpochMillis 최초 점검 가능 시각 (예약 지연 + grace 이후)
     */
    public void enqueueRoundRecovery(String lobbyCode, int expectedRoundNo, long nextRetryAtEpochMillis) {
        String payload = String.join(
                PAYLOAD_DELIMITER,
                lobbyCode,
                String.valueOf(expectedRoundNo),
                INITIAL_ATTEMPT,
                String.valueOf(nextRetryAtEpochMillis)
        );

        try {
            redisTemplate.opsForList().rightPush(RedisKeys.GAME_ROUND_RECOVERY_QUEUE, payload);
            incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_ENQUEUED);
        } catch (Exception e) {
            incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
            log.error("[MONITORING_REQUIRED] 다음 라운드 복구 큐 적재 실패 - code: {}, expectedRoundNo: {}",
                    lobbyCode, expectedRoundNo, e);
        }
    }

    /**
     * 복구 queue에서 payload를 하나 꺼낸다. queue가 비어 있으면 null을 반환한다.
     */
    public String pollRoundRecovery() {
        return redisTemplate.opsForList().leftPop(RedisKeys.GAME_ROUND_RECOVERY_QUEUE);
    }

    /**
     * 아직 처리 시각이 아니거나 재시도가 필요한 payload를 queue 뒤쪽에 다시 적재한다.
     */
    public void requeueRoundRecovery(String payload) {
        redisTemplate.opsForList().rightPush(RedisKeys.GAME_ROUND_RECOVERY_QUEUE, payload);
    }

    /**
     * 복구 워커의 최상위 보상 경로에서 호출하는 안전 재적재 메서드.
     *
     * 복구 워커는 leftPop으로 payload를 큐에서 꺼낸 뒤 처리하므로, 처리 중 예외가 나면 원본 payload가
     * 유실될 수 있다. 이 메서드는 rightPush 실패까지도 흡수해(보상 경로가 다시 예외로 중단되지 않도록)
     * 원본 payload를 best-effort로 다시 큐에 넣는다. 재적재마저 실패하면 ALERT 로그와 실패 metric을 남긴다.
     */
    public void safeRequeueRoundRecovery(String payload) {
        try {
            redisTemplate.opsForList().rightPush(RedisKeys.GAME_ROUND_RECOVERY_QUEUE, payload);
        } catch (Exception e) {
            incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
            log.error("[ALERT_REQUIRED] 다음 라운드 복구 payload 안전 재적재 실패 - payload 유실. payload: {}", payload, e);
        }
    }

    /**
     * 복구 관련 Redis metric counter를 증가시킨다. metric 증가 실패가 흐름을 막지 않도록 흡수한다.
     */
    public void incrementRoundRecoveryMetric(String metricKey) {
        try {
            redisTemplate.opsForValue().increment(metricKey);
        } catch (Exception e) {
            log.warn("다음 라운드 복구 metric 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}
