/*
전체 채팅 및 로비 채팅 메시지를 수신하여 Redis Pub/Sub으로 발행하는 WebSocket 컨트롤러

[책임]
- STOMP @MessageMapping 경로에 따라 메시지를 수신하고 RedisPublisher로 위임
- 컨트롤러는 라우팅만 담당하며, 메시지 가공 로직은 포함하지 않음

 TODO: Commit #6(ChatController 인프라 로직 분리)에서
      extractSenderIdentifier(), createSecureMessage() 로직을 ChatService로 이전 예정
 */
package io.github.ascrew.monomatbe.domain.chat.controller;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
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
     * 전체 채팅 라우팅
     * 클라이언트 송신: /app/chat/global
     * 클라이언트 수신(구독): /topic/chat/global
     */
    @MessageMapping("/chat/global")
    public void broadcastGlobal(ChatMessageDto message, SimpMessageHeaderAccessor accessor) {
        String senderIdentifier = extractSenderIdentifier(accessor);
        ChatMessageDto secureMessage = createSecureMessage(message, "global", senderIdentifier);
        redisPublisher.publish("/topic/chat/global", secureMessage);
    }

    /*
     * 로비 전용 채팅 라우팅
     * 클라이언트 송신: /app/chat/lobby/{code}
     * 클라이언트 수신(구독): /topic/lobby/{code}
     */
    @MessageMapping("/chat/lobby/{code}")
    public void broadcastLobby(
            @DestinationVariable String code,
            ChatMessageDto message,
            SimpMessageHeaderAccessor accessor
    ) {
        String senderIdentifier = extractSenderIdentifier(accessor);
        ChatMessageDto secureMessage = createSecureMessage(message, code, senderIdentifier);
        redisPublisher.publish("/topic/lobby/" + code, secureMessage);
    }

    /*
     * 세션 속성에서 사용자 식별자를 추출합니다.
     * StompChannelInterceptor에서 CONNECT 시점에 저장한 값을 읽어옵니다.
     *
     * TODO: Commit #3(uuid 네이밍 통일)에서 세션 키를 WebSocketHeaders 상수로 교체 예정
     * TODO: Commit #6(ChatController 인프라 로직 분리)에서 ChatService로 이전 예정
     */
    private String extractSenderIdentifier(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        return (sessionAttributes != null && sessionAttributes.get("uuid") != null)
                ? (String) sessionAttributes.get("uuid")
                : "Unknown";
    }

    /*
     * 클라이언트에서 전달받은 메시지를 서버 신뢰 데이터로 재구성합니다.
     * sender를 클라이언트 입력값이 아닌 세션에서 추출한 값으로 덮어써서
     * 발신자 위변조를 방지합니다.
     *
     * TODO: Commit #6(ChatController 인프라 로직 분리)에서 ChatService로 이전 예정
     */
    private ChatMessageDto createSecureMessage(
            ChatMessageDto message,
            String roomId,
            String senderIdentifier
    ) {
        return ChatMessageDto.builder()
                .type(message.getType())
                .roomId(roomId)
                .sender(senderIdentifier)
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .build();
    }
}
