/*
전체 채팅 라우팅
로비 채팅 라우팅
밑에 주석 참조
 */
package io.github.ascrew.monomatbe.chat.controller;

import io.github.ascrew.monomatbe.messaging.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.common.annotation.SessionUuid;
import io.github.ascrew.monomatbe.common.constant.WebSocketConstants;
import io.github.ascrew.monomatbe.messaging.redis.RedisPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final RedisPublisher redisPublisher;

    /*
     * 1. 전체 채팅 라우팅
     * 클라이언트 송신: /app/chat/global
     * 클라이언트 수신(구독): /topic/global
     */
    @MessageMapping(WebSocketConstants.CHAT_GLOBAL_MAPPING)
    public void broadcastGlobal(ChatMessageDto message, @SessionUuid String uuid) {
        ChatMessageDto secureMessage = createSecureMessage(message, WebSocketConstants.CHAT_GLOBAL_ROOM_ID, uuid);
        redisPublisher.publish(WebSocketConstants.CHAT_GLOBAL_TOPIC, secureMessage);

    }

    /*
     * 2. 로비 전용 채팅 라우팅
     * 클라이언트 송신: /app/chat/lobby/{code} (ex: /app/chat/lobby/난수+문자 6자리)
     * 클라이언트 수신(구독): /topic/lobby/{code}
     */
    @MessageMapping(WebSocketConstants.CHAT_LOBBY_MAPPING)
    public void broadcastLobby(@DestinationVariable("code")String code, ChatMessageDto message, @SessionUuid String uuid) {
        ChatMessageDto secureMessage = createSecureMessage(message, code, uuid);
        redisPublisher.publish(WebSocketConstants.LOBBY_TOPIC_PREFIX + code, secureMessage);

    }

    private ChatMessageDto createSecureMessage(ChatMessageDto message, String secureRoomId, String secureSenderUuid){
        return ChatMessageDto.builder()
                .type(message.type())
                .roomId(secureRoomId)
                .sender(secureSenderUuid)
                .content(message.content())
                .timestamp(message.timestamp())
                .build();
    }
}
