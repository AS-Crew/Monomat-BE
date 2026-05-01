package io.github.ascrew.monomatbe.domain.chat.controller;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
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
        String userIdentifier = extractUserIdentifier(accessor);
        ChatMessageDto secureMessage = createSecureMessage(message, "global", userIdentifier);
        redisPublisher.publish(StompDestinations.SUBSCRIBE_GLOBAL_CHAT, secureMessage);
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
        String userIdentifier = extractUserIdentifier(accessor);
        ChatMessageDto secureMessage = createSecureMessage(message, code, userIdentifier);
        redisPublisher.publish(StompDestinations.subscribeLobbyChat(code), secureMessage);
    }

    /*
     * 세션 속성에서 사용자 식별자를 추출합니다.
     * StompChannelInterceptor에서 CONNECT 시점에 WebSocketHeaders.USER_IDENTIFIER 키로
     * 저장한 값을 읽어옵니다.
     *
     * TODO: Commit #6(ChatController 인프라 로직 분리)에서 ChatService로 이전 예정
     */
    private String extractUserIdentifier(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return WebSocketHeaders.UNKNOWN_IDENTIFIER;
        }
        Object identifier = sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        return identifier != null
                ? (String) identifier
                : WebSocketHeaders.UNKNOWN_IDENTIFIER;
    }

    /*
     * 클라이언트 메시지를 서버 신뢰 데이터로 재구성합니다.
     * sender를 세션에서 추출한 값으로 덮어써서 발신자 위변조를 방지합니다.
     *
     * TODO: Commit #6(ChatController 인프라 로직 분리)에서 ChatService로 이전 예정
     */
    private ChatMessageDto createSecureMessage(
            ChatMessageDto message,
            String roomId,
            String userIdentifier
    ) {
        return ChatMessageDto.builder()
                .type(message.getType())
                .roomId(roomId)
                .sender(userIdentifier)
                .content(message.getContent())
                .timestamp(message.getTimestamp())
                .build();
    }
}