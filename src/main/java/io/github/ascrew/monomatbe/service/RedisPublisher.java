package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisPublisher(RedisTemplate<String , Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(String topic, ChatMessageDto messageDto) {
        // Redis의 특정 채널(topic)에 메시지를 발행
        redisTemplate.convertAndSend(topic, messageDto);
    }
}
