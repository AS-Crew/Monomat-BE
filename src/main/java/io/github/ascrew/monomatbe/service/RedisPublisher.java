package io.github.ascrew.monomatbe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPublisher(RedisTemplate<String , Object> redisTemplate) {
        this.objectMapper = new ObjectMapper();
        this.redisTemplate = redisTemplate;
    }

    public void publish(String topic, ChatMessageDto messageDto) {
        try {
            String JsonMessage = objectMapper.writeValueAsString(messageDto);  //ChatMessageDto 객체를 JSON 문자열로 직렬화

            // Redis의 특정 채널(topic)에 메시지를 발행
            redisTemplate.convertAndSend(topic, JsonMessage);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
