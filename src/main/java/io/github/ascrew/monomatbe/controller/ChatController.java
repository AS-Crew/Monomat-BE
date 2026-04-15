/*
전체 채팅 라우팅
로비 채팅 라우팅
밑에 주석 참조
 */
package io.github.ascrew.monomatbe.controller;

import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.service.RedisPublisher;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    private final RedisPublisher redisPublisher;

    public ChatController(RedisPublisher redisPublisher) {
        this.redisPublisher = redisPublisher;
    }


    /*
     * 1. 전체 채팅 라우팅
     * 클라이언트 송신: /app/chat/global
     * 클라이언트 수신(구독): /topic/global
     */
    @MessageMapping("/chat/global")
    public void broadcastGlobal(ChatMessageDto message){
        redisPublisher.publish("/topic/chat/global", message);

    }

    /*
     * 2. 로비 전용 채팅 라우팅
     * 클라이언트 송신: /app/chat/lobby/{code} (ex: /app/chat/lobby/난수+문자 6자리)
     * 클라이언트 수신(구독): /topic/lobby/{code}
     */
    @MessageMapping("/chat/lobby/{code}")
    public void broadcastLobby(@DestinationVariable("code")String code, ChatMessageDto message){
        redisPublisher.publish("/topic/lobby/" + code, message);

    }
}
