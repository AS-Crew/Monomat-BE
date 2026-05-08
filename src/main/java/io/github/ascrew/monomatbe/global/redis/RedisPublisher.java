/*
 * Redis Pub/Sub 채널에 메시지를 발행하는 인프라 컴포넌트.
 *
 * [global/redis로 분류한 이유]
 * RedisPublisher는 특정 도메인(chat, lobby 등)에 종속되지 않고
 * 어느 도메인에서든 Redis 채널에 메시지를 발행할 때 재사용되므로,
 * domain이 아닌 global/redis 패키지에 위치한다.
 *
 * [직렬화 정책]
 * Pub/Sub 메시지는 WebSocket 클라이언트에게 그대로 전달될 수 있으므로,
 * RedisTemplate<String, Object>의 타입 정보 포함 직렬화를 사용하지 않는다.
 *
 * 대신 StringRedisTemplate과 Pub/Sub 전용 JsonMapper를 사용하여
 * 프론트엔드가 바로 JSON.parse() 가능한 순수 JSON 문자열을 발행한다.
 *
 * [장애 처리 정책]
 * Redis Pub/Sub 발행 실패는 ENTER/LEAVE 같은 시스템 메시지 유실로 이어질 수 있다.
 * 따라서 예외를 단순히 로그로만 삼키지 않고 다음 처리를 수행한다.
 *
 * 1. 발행 실패 시 제한된 횟수만큼 재시도
 * 2. 최종 실패 시 false 반환
 * 3. 성공/실패 메트릭 기록
 * 4. 호출부가 반환값을 기반으로 추가 fallback 처리를 할 수 있게 함
 */
package io.github.ascrew.monomatbe.global.redis;

import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Slf4j
@Service
public class RedisPublisher {

    // =========================================================
    // 재시도 정책 상수
    // =========================================================

    /**
     * Redis Pub/Sub 발행 최대 시도 횟수.
     *
     * 최초 1회 + 재시도 1회입니다.
     * WebSocket 이벤트 처리 흐름을 과도하게 지연시키지 않기 위해 무제한 재시도는 하지 않는다.
     */
    private static final int MAX_PUBLISH_ATTEMPTS = 2;

    /**
     * Redis Pub/Sub 발행 재시도 전 대기 시간.
     *
     * 아주 짧은 네트워크 흔들림이나 Redis 커넥션 순간 오류를 흡수하기 위한 값
     */
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(50);

    /**
     * 로그에 남길 payload 최대 길이.
     *
     * 메시지가 과도하게 길어질 경우 로그가 오염될 수 있으므로 제한한다.
     */
    private static final int MAX_PAYLOAD_LOG_LENGTH = 500;

    // =========================================================
    // 메트릭 이름 상수
    // =========================================================

    private static final String METRIC_PUBLISH_SUCCESS = "redis.pubsub.publish.success";
    private static final String METRIC_PUBLISH_FAILURE = "redis.pubsub.publish.failure";

    // =========================================================
    // 의존성
    // =========================================================

    /**
     * Redis Pub/Sub 발행 전용 Template.
     *
     * 순수 JSON 문자열을 발행해야 하므로 StringRedisTemplate을 사용한다.
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Pub/Sub 전용 JsonMapper.
     *
     * Redis 데이터 저장용 JsonMapper는 activateDefaultTyping을 사용할 수 있으므로,
     * Pub/Sub 메시지 직렬화에는 별도 Mapper를 사용한다.
     */
    private final JsonMapper pubSubJsonMapper;

    /**
     * Pub/Sub 발행 성공 메트릭.
     */
    private final Counter publishSuccessCounter;

    /**
     * Pub/Sub 발행 실패 메트릭.
     */
    private final Counter publishFailureCounter;

    public RedisPublisher(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper,
            MeterRegistry meterRegistry
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.pubSubJsonMapper = pubSubJsonMapper;
        this.publishSuccessCounter = Counter.builder(METRIC_PUBLISH_SUCCESS)
                .description("Redis Pub/Sub publish success count")
                .register(meterRegistry);
        this.publishFailureCounter = Counter.builder(METRIC_PUBLISH_FAILURE)
                .description("Redis Pub/Sub publish failure count")
                .register(meterRegistry);
    }

    /**
     * 지정한 Redis Pub/Sub 채널로 메시지를 발행한다.
     *
     * [반환값]
     * - true  : 발행 성공
     * - false : 직렬화 실패 또는 모든 재시도 실패
     *
     * [주의]
     * false가 반환되면 시스템 메시지가 실제 클라이언트에게 전달되지 않았을 수 있다.
     * 호출부는 필요하면 fallback 로그, 직접 WebSocket 전송, 사용자 재시도 안내 등을 수행해야 한다.
     *
     * @param topic      Redis Pub/Sub 채널명. WebSocket destination과 동일하게 사용.
     * @param messageDto 발행할 메시지 DTO
     * @return 발행 성공 여부
     */
    public boolean publish(String topic, ChatMessageDto messageDto) {
        if (topic == null || topic.isBlank()) {
            log.error("Redis Pub/Sub 발행 실패 - topic이 비어 있습니다. messageType: {}",
                    messageDto != null ? messageDto.getType() : null);
            publishFailureCounter.increment();
            return false;
        }

        if (messageDto == null) {
            log.error("Redis Pub/Sub 발행 실패 - messageDto가 null입니다. topic: {}", topic);
            publishFailureCounter.increment();
            return false;
        }

        String payload;
        try {
            payload = pubSubJsonMapper.writeValueAsString(messageDto);
        } catch (Exception e) {
            log.error("Redis Pub/Sub 메시지 직렬화 실패 - topic: {}, messageType: {}",
                    topic,
                    messageDto.getType(),
                    e);
            publishFailureCounter.increment();
            return false;
        }

        return publishWithRetry(topic, messageDto, payload);
    }

    /**
     * Redis Pub/Sub 발행을 제한된 횟수만큼 재시도합니다.
     *
     * 직렬화는 재시도해도 결과가 달라질 가능성이 낮으므로 publish()에서 한 번만 수행합니다.
     * 이 메서드는 Redis convertAndSend 실패에 대해서만 재시도합니다.
     */
    private boolean publishWithRetry(
            String topic,
            ChatMessageDto messageDto,
            String payload
    ) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_PUBLISH_ATTEMPTS; attempt++) {
            try {
                stringRedisTemplate.convertAndSend(topic, payload);

                publishSuccessCounter.increment();

                if (attempt > 1) {
                    log.info("Redis Pub/Sub 발행 재시도 성공 - topic: {}, messageType: {}, attempt: {}/{}",
                            topic, messageDto.getType(), attempt, MAX_PUBLISH_ATTEMPTS);
                }

                return true;

            } catch (Exception e) {
                lastException = e;

                log.warn("Redis Pub/Sub 발행 실패 - 재시도 여부 확인. topic: {}, messageType: {}, attempt: {}/{}, message: {}",
                        topic,
                        messageDto.getType(),
                        attempt,
                        MAX_PUBLISH_ATTEMPTS,
                        e.getMessage());

                waitBeforeRetry(attempt);
            }
        }

        publishFailureCounter.increment();

        log.error("Redis Pub/Sub 발행 최종 실패 - topic: {}, messageType: {}, payload: {}",
                topic,
                messageDto.getType(),
                abbreviatePayload(payload),
                lastException);

        return false;
    }

    /**
     * 다음 재시도 전에 짧게 대기
     *
     * 마지막 시도 이후에는 대기하지 않는다.
     * interrupt 발생 시 인터럽트 상태를 복원하고 즉시 반환한다.
     */
    private void waitBeforeRetry(int attempt) {
        if (attempt >= MAX_PUBLISH_ATTEMPTS) {
            return;
        }

        try {
            Thread.sleep(RETRY_BACKOFF.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Redis Pub/Sub 재시도 대기 중 인터럽트 발생");
        }
    }

    /**
     * payload 로그 길이를 제한한다.
     */
    private String abbreviatePayload(String payload) {
        if (payload == null) {
            return "null";
        }

        if (payload.length() <= MAX_PAYLOAD_LOG_LENGTH) {
            return payload;
        }

        return payload.substring(0, MAX_PAYLOAD_LOG_LENGTH) + "...";
    }
}