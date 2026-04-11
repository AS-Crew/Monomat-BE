/*
전체 채팅 라우팅
로비 채팅 라우팅
밑에 주석 참조
 */
package io.github.ascrew.monomatbe.controller;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // 메시지 타입 정의
    public enum MessageType {
        CHAT, ANSWER, ENTER, LEAVE
    }

    // DTO 레코드 생성
    public record ChatMessageDto(
            MessageType type,
            String sender,
            String content,
            String timestamp
    ) {}

    /*
     * 1. 전체 채팅 라우팅
     * 클라이언트 송신: /app/chat/global
     * 클라이언트 수신(구독): /topic/global
     */
    @MessageMapping("/chat/global")
    @SendTo("/topic/chat/global")
    public String broadcastGlobal(String message){

        return message;
    }

    /*
     * 2. 로비 전용 채팅 라우팅
     * 클라이언트 송신: /app/chat/lobby/{code} (ex: /app/chat/lobby/난수+문자 6자리)
     * 클라이언트 수신(구독): /topic/lobby/{code}
     */
    @MessageMapping("/chat/lobby/{code}")
    @SendTo("/topic/chat/lobby/{code}")
    public void broadcastLobby(@DestinationVariable("code")String code, String message){
        messagingTemplate.convertAndSend("/topic/lobby/" + code, message);

    }
}
