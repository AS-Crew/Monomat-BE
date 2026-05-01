/*
 * Redis Pub/Sub 채널에 메시지를 발행하는 인프라 컴포넌트.
 *
 * [global/redis로 분류한 이유]
 * RedisPublisher는 특정 도메인(chat, lobby 등)에 종속되지 않고 어느 도메인에서든 Redis 채널에 메시지를 발행할 때 재사용되므로,
 * domain이 아닌 global/redis 패키지에 위치한다.
 */
package io.github.ascrew.monomatbe.global.redis;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 지정한 Redis 채널 토픽으로 메시지를 발행합니다.
     *
     * @param topic      발행할 Redis 채널 경로
     * @param messageDto 발행할 채팅 메시지 객체
     */
    public void publish(String topic, ChatMessageDto messageDto) {
        redisTemplate.convertAndSend(topic, messageDto);
    }
}