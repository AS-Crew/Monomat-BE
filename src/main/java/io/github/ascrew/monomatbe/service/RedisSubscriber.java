package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final RedisTemplate<String, Object> redisTemplate;      // RedisTemplate을 사용하여 Redis와 상호작용
    private final SimpMessagingTemplate simpMessagingTemplate;      // WebSocket을 통해 클라이언트에게 메시지를 전송하기 위한 SimpMessagingTemplate

    @Override
    public void onMessage(Message message, byte[] pattern){
        try{
            byte[] body =message.getBody();
            ChatMessageDto chatMessageDto = (ChatMessageDto) redisTemplate.getValueSerializer().deserialize(body); //메시지 바디를 역직렬화하여 ChatMessageDto 객체로 변환

            if(chatMessageDto != null){
                String destination = new String(message.getChannel());
                simpMessagingTemplate.convertAndSend(destination, chatMessageDto);
            }

        }catch (Exception e){
            log.error("Redis 메시지 파싱 및 브로드캐스트 실패. 채널: {}",new String(message.getChannel()));               //예외 발생 시 스택 트레이스 출력
        }
    }
}

