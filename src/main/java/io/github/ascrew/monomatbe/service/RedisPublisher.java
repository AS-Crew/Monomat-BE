package io.github.ascrew.monomatbe.service;

import tools.jackson.databind.json.JsonMapper;
import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
public class RedisPublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JsonMapper jsonMapper;

    public RedisPublisher(RedisTemplate<String , Object> redisTemplate, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.redisTemplate = redisTemplate;
    }

    public void publish(String topic, ChatMessageDto messageDto) {
        try {
            String JsonMessage = jsonMapper.writeValueAsString(messageDto);    //ChatMessageDto 객체를 JSON 문자열로 직렬화

            // Redis의 특정 채널(topic)에 메시지를 발행
            redisTemplate.convertAndSend(topic, JsonMessage);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
