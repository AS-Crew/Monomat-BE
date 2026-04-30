package io.github.ascrew.monomatbe.messaging.redis;

import lombok.RequiredArgsConstructor;
import io.github.ascrew.monomatbe.messaging.dto.ChatMessageDto;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    public void publish(String topic, ChatMessageDto messageDto) {
        redisTemplate.convertAndSend(topic, messageDto);
    }

}

