/*
전체 채팅 라우팅
로비 채팅 라우팅
밑에 주석 참조
 */
package io.github.ascrew.monomatbe.controller;

import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.service.RedisPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final RedisPublisher redisPublisher;

    /*
     * 1. 전체 채팅 라우팅
     * 클라이언트 송신: /app/chat/global
     * 클라이언트 수신(구독): /topic/global
     */
    @MessageMapping("/chat/global")
    public void broadcastGlobal(ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        String uuid = extractUuid(accessor);
        ChatMessageDto secureMessage = createSecureMessage(message, "global", uuid);
        redisPublisher.publish("/topic/chat/global", secureMessage);

    }

    /*
     * 2. 로비 전용 채팅 라우팅
     * 클라이언트 송신: /app/chat/lobby/{code} (ex: /app/chat/lobby/난수+문자 6자리)
     * 클라이언트 수신(구독): /topic/lobby/{code}
     */
    @MessageMapping("/chat/lobby/{code}")
    public void broadcastLobby(@DestinationVariable("code")String code, ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        String uuid = extractUuid(accessor);
        ChatMessageDto secureMessage = createSecureMessage(message, code, uuid);
        redisPublisher.publish("/topic/lobby/" + code, secureMessage);

    }

    private String extractUuid(SimpMessageHeaderAccessor headerAccessor){
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        return (sessionAttributes != null && sessionAttributes.get("uuid") != null)
                ? (String) sessionAttributes.get("uuid"): "Unknown";
    }

    private ChatMessageDto createSecureMessage(ChatMessageDto message, String secureRoomId, String secureSenderUuid){
        return ChatMessageDto.builder()
                .type(message.getType())
                .roomId(secureRoomId)
                .sender(secureSenderUuid)
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .build();
    }
}
