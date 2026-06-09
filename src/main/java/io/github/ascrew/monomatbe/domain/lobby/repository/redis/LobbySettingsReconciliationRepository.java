package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 로비 설정 Redis-DB 정합성 재처리 큐 Repository
 *
 * [책임]
 * - 로비 설정 변경 중 DB 갱신 실패 후 Redis 설정 복구까지 실패한 케이스를 별도 큐에 적재한다.
 *
 * [분리 이유]
 * 게임 시작 재처리 큐는 start_lobby.lua 이후 Redis/DB PLAYING 상태 불일치를 다룬다.
 * 로비 설정 복구 실패는 max_players/question_count/time_limit_seconds 불일치 문제이므로
 * start reconciliation queue에 넣으면 운영 로그와 후속 처리 주체가 잘못된다.
 *
 * [실패 정책]
 * 이 Repository는 장애 복구 경로에서 호출된다.
 * 큐 적재 실패가 원래 API 실패 응답을 덮어쓰면 안 되므로, 내부에서 예외를 삼키고 로그/metric만 남긴다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbySettingsReconciliationRepository {

    private static final String DELIMITER = "|";
    private static final String INITIAL_RETRY_COUNT = "0";
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

    private final StringRedisTemplate redisTemplate;

    /**
     * 로비 설정 정합성 재처리 payload를 큐에 적재한다.
     *
     * payload 형식:
     * {code}|{reason}|{retryCount}|{createdAtEpochMillis}
     *
     * @param code 로비 초대 코드
     * @param reason 재처리 사유
     */
    public void enqueueSettingsReconciliation(String code, String reason) {
        String payload = String.join(
                DELIMITER,
                code,
                reason,
                INITIAL_RETRY_COUNT,
                String.valueOf(System.currentTimeMillis())
        );

        try {
            redisTemplate.opsForList().rightPush(
                    RedisKeys.LOBBY_SETTINGS_RECONCILIATION_QUEUE,
                    payload
            );

            incrementMetric(RedisKeys.METRIC_LOBBY_SETTINGS_RECONCILIATION_ENQUEUED);

            log.error(
                    "{} 로비 설정 재처리 큐 적재 완료 - code: {}, reason: {}, queueKey: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    reason,
                    RedisKeys.LOBBY_SETTINGS_RECONCILIATION_QUEUE
            );

        } catch (Exception e) {
            incrementMetric(RedisKeys.METRIC_LOBBY_SETTINGS_RECONCILIATION_FAILED);

            log.error(
                    "{} 로비 설정 재처리 큐 적재 실패 - code: {}, reason: {}, queueKey: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    reason,
                    RedisKeys.LOBBY_SETTINGS_RECONCILIATION_QUEUE,
                    e
            );
        }
    }

    private void incrementMetric(String metricKey) {
        try {
            redisTemplate.opsForValue().increment(metricKey);
        } catch (Exception e) {
            log.warn("로비 설정 재처리 metric 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}