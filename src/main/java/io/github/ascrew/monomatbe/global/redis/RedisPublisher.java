/*
 * Redis Pub/Sub 채널에 메시지를 발행하는 인프라 컴포넌트.
 *
 * [역할]
 * 서버 내부에서 발생한 채팅/입장/퇴장 메시지를 Redis Pub/Sub 채널로 발행한다.
 *
 * [중요]
 * Pub/Sub 메시지는 WebSocket 클라이언트에게 그대로 전달될 수 있으므로,
 * RedisTemplate<String, Object>의 타입 정보 포함 직렬화를 사용하지 않는다.
 *
 * 대신 StringRedisTemplate과 Pub/Sub 전용 JsonMapper를 사용하여
 * 프론트엔드가 바로 JSON.parse() 가능한 순수 JSON 문자열을 발행한다.
 */
package io.github.ascrew.monomatbe.global.redis;

import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
public class RedisPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper pubSubJsonMapper;

    public RedisPublisher(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.pubSubJsonMapper = pubSubJsonMapper;
    }

    /**
     * 지정한 Redis Pub/Sub 채널로 메시지를 발행한다.
     *
     * [직렬화 정책]
     * - ChatMessageDto를 순수 JSON 문자열로 변환한다.
     * - 클래스 타입 정보는 포함하지 않는다.
     * - Redis 채널에는 문자열 그대로 저장/전송한다.
     */
    public void publish(String topic, ChatMessageDto messageDto) {
        try {
            String payload = pubSubJsonMapper.writeValueAsString(messageDto);
            stringRedisTemplate.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.error("Redis Pub/Sub 메시지 직렬화 실패 - topic: {}, messageType: {}",
                    topic,
                    messageDto != null ? messageDto.getType() : null,
                    e);
        }
    }
}