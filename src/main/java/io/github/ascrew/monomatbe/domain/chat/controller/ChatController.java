/*
 * 전체 채팅 및 로비 채팅 메시지를 수신하여 ChatService에 위임하는 WebSocket 컨트롤러.
 *
 * [책임]
 * STOMP @MessageMapping 경로에 따라 메시지를 수신하고 ChatService로 위임합니다.
 * 컨트롤러는 라우팅만 담당하며 메시지 처리 로직을 포함하지 않습니다.
 */
package io.github.ascrew.monomatbe.domain.chat.controller;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.domain.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 전체 채팅 메시지 수신 및 위임.
     * 클라이언트 송신: /app/chat/global
     * 클라이언트 수신(구독): /topic/chat/global
     */
    @MessageMapping("/chat/global")
    public void broadcastGlobal(ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        chatService.publishGlobalMessage(message, accessor);
    }

    /**
     * 로비 전용 채팅 메시지 수신 및 위임.
     * 클라이언트 송신: /app/chat/lobby/{code}
     * 클라이언트 수신(구독): /topic/lobby/{code}
     */
    @MessageMapping("/chat/lobby/{code}")
    public void broadcastLobby(
            @DestinationVariable String code,
            ChatMessageDto message,
            SimpMessageHeaderAccessor accessor
    ) {
        chatService.publishLobbyMessage(code, message, accessor);
    }
}