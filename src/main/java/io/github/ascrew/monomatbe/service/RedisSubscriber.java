package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;


@Service
public class RedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;                        // JSON 직렬화/역직렬화를 위한 ObjectMapper
    private final RedisTemplate<String, Object> redisTemplate;      // RedisTemplate을 사용하여 Redis와 상호작용
    private final SimpMessagingTemplate simpMessagingTemplate;      // WebSocket을 통해 클라이언트에게 메시지를 전송하기 위한 SimpMessagingTemplate

    public RedisSubscriber(ObjectMapper objectMapper, RedisTemplate<String , Object> redisTemplate, SimpMessagingTemplate simpMessagingTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.simpMessagingTemplate = simpMessagingTemplate;
    }

    @Override
    public void onMessage(Message message, byte[] pattern){
        try{
            String channel = new String(message.getChannel());                                                  //문자열로 채널 이름 추출
            String messageBody = (String) redisTemplate.getValueSerializer().deserialize(message.getBody());    //메시지 바디를 문자열로 역직렬화
            ChatMessageDto chatMessageDto = objectMapper.readValue(messageBody, ChatMessageDto.class);          //문자열로 된 메시지 바디를 ChatMessageDto 객체로 역직렬화
            simpMessagingTemplate.convertAndSend(channel, chatMessageDto);                                      //WebSocket을 통해 해당 채널을 구독 중인 클라이언트에게 메시지 전송

        }catch (Exception e){
            e.printStackTrace();                                                          //예외 발생 시 스택 트레이스 출력
        }
    }
}

