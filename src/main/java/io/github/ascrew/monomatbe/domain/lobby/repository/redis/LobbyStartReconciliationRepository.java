package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 로비 게임 시작 Redis-DB 불일치 재처리 queue 전용 Repository
 *
 * [사용 목적]
 * start_lobby.lua 실행 이후 Redis는 PLAYING으로 변경되었지만,
 * DB GAME_LOBBY 상태 변경 또는 Redis 보상 롤백이 실패할 수 있다.
 *
 * 이 경우 운영자가 수동 확인할 수 있도록 로그를 남기고,
 * 후속 reconciliation worker가 처리할 수 있도록 Redis queue에 payload를 적재한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyStartReconciliationRepository {

    /**
     * Redis queue payload 구분자
     * payload = code|reason|retryCount|timestamp
     */
    private static final String RECONCILIATION_PAYLOAD_DELIMITER = "|";

    /**
     * 운영 확인이 필요한 Redis 정합성 문제 로그 식별자
     */
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

    /**
     * 신규 reconciliation payload의 초기 재시도 횟수
     */
    private static final String INITIAL_RETRY_COUNT = "0";

    private final StringRedisTemplate redisTemplate;

    /**
     * Redis-DB 상태 불일치 재처리 요청을 Redis queue에 적재한다.
     *
     * [payload 형식]
     * code|reason|retryCount|timestamp
     *
     * [실패 처리]
     * queue 적재 실패는 상태 불일치가 남을 수 있는 운영 이슈이므로 ERROR 로그를 남긴다.
     * 단, 이 메서드 자체는 예외를 다시 던지지 않는다.
     * 기존 LobbyRepositoryImpl 동작과 동일하게 호출 흐름을 유지한다.
     *
     * @param code 로비 초대 코드
     * @param reason 재처리 사유
     */
    public void enqueueStartReconciliation(String code, String reason) {
        String payload = String.join(
                RECONCILIATION_PAYLOAD_DELIMITER,
                code,
                reason,
                INITIAL_RETRY_COUNT,
                String.valueOf(System.currentTimeMillis())
        );

        try {
            redisTemplate.opsForList().rightPush(
                    RedisKeys.LOBBY_START_RECONCILIATION_QUEUE,
                    payload
            );

            incrementStartReconciliationMetric(
                    RedisKeys.METRIC_LOBBY_START_RECONCILIATION_ENQUEUED
            );

            log.error(
                    "{} 게임 시작 상태 재처리 큐 적재 완료 - code: {}, reason: {}, queueKey: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    reason,
                    RedisKeys.LOBBY_START_RECONCILIATION_QUEUE
            );
        } catch (Exception e) {
            incrementStartReconciliationMetric(
                    RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED
            );

            log.error(
                    "{} 게임 시작 상태 재처리 큐 적재 실패 - code: {}, reason: {}, queueKey: {}. "
                            + "Redis-DB 불일치 수동 확인이 필요합니다.",
                    LOG_MONITORING_REQUIRED,
                    code,
                    reason,
                    RedisKeys.LOBBY_START_RECONCILIATION_QUEUE,
                    e
            );
        }
    }

    /**
     * Redis-DB 상태 불일치 재처리 queue에서 payload를 하나 꺼낸다.
     *
     * [주의]
     * leftPop은 queue가 비어 있으면 null을 반환한다.
     * 호출자는 null 여부를 확인해야 한다.
     *
     * @return "code|reason|retryCount|timestamp" 형태의 payload. 없으면 null.
     */
    public String pollStartReconciliation() {
        return redisTemplate.opsForList().leftPop(
                RedisKeys.LOBBY_START_RECONCILIATION_QUEUE
        );
    }

    /**
     * 재처리에 실패한 payload를 queue 뒤쪽에 다시 적재한다.
     *
     * [정책]
     * retryCount 증가나 dead-letter 분리는 향후 reconciliation worker 단계에서 처리한다.
     * 이번 리팩토링 단계에서는 기존 payload를 그대로 다시 넣는 동작을 유지한다.
     *
     * @param payload 재처리 payload
     */
    public void requeueStartReconciliation(String payload) {
        redisTemplate.opsForList().rightPush(
                RedisKeys.LOBBY_START_RECONCILIATION_QUEUE,
                payload
        );
    }

    /**
     * 게임 시작 상태 재처리 관련 Redis metric counter를 증가시킨다.
     *
     * [정책]
     * metric 증가 실패가 실제 로비 흐름을 실패시키면 안 된다.
     * 따라서 실패 시 warn 로그만 남기고 흐름은 유지한다.
     *
     * @param metricKey Redis metric key
     */
    public void incrementStartReconciliationMetric(String metricKey) {
        try {
            redisTemplate.opsForValue().increment(metricKey);
        } catch (Exception e) {
            log.warn("게임 시작 상태 재처리 metric 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}