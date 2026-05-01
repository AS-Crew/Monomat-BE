package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    // 사용자 온라인 상태 TTL (시간 단위)
    private static final long USER_STATUS_TTL_HOURS = 2;

    private final RedisPublisher redisPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketMetric webSocketMetric;

    /**
     * WebSocket 연결 성공 이벤트 처리.
     * Redis에 사용자 온라인 상태를 저장하고 활성 세션 수를 증가시킵니다.
     */
    @EventListener
    public void handleConnectEvent(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        String userIdentifier = extractUserIdentifier(sessionAttributes);

        if (WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) {
            log.warn("인증되지 않은 세션 연결 감지");
            return;
        }

        // RedisKeys 상수로 키 생성
        redisTemplate.opsForValue().set(
                RedisKeys.userStatusKey(userIdentifier),
                WebSocketHeaders.STATUS_ONLINE,
                USER_STATUS_TTL_HOURS,
                TimeUnit.HOURS
        );

        webSocketMetric.increment();
        log.info("WebSocket 연결 - 식별자: {}", userIdentifier);
    }

    /**
     * WebSocket 연결 해제 이벤트 처리.
     * Redis에서 사용자 상태를 제거하고, 참여 중인 로비에 퇴장 메시지를 브로드캐스트합니다.
     *
     * TODO: 이슈 #5(이중 리스너 통합)에서 LobbyConnectionListener 로직을 이곳으로 통합 예정
     */
    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) return;

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        String roomId = (String) sessionAttributes.get(WebSocketHeaders.ROOM_ID);

        if (userIdentifier == null || WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) return;

        // Redis에서 사용자 온라인 상태 제거
        redisTemplate.delete(RedisKeys.userStatusKey(userIdentifier));
        webSocketMetric.decrement();

        log.info("WebSocket 연결 해제 - 식별자: {}, 로비: {}", userIdentifier, roomId);

        if (roomId != null) {
            // StompDestinations 상수로 경로 생성
            redisPublisher.publish(
                    StompDestinations.subscribeLobbyChat(roomId),
                    ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.LEAVE)
                            .roomId(roomId)
                            .sender(userIdentifier)
                            .content(userIdentifier + "님이 퇴장하셨습니다.")
                            .build()
            );

            // RedisKeys 상수로 키 생성
            redisTemplate.opsForSet().remove(RedisKeys.userRoomKey(roomId), userIdentifier);
        }
    }

    /**
     * WebSocket 채널 구독 이벤트 처리.
     * 로비 채널 구독 시 Redis 참여자 Set에 사용자를 추가합니다.
     */
    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) return;

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        String destination = accessor.getDestination();

        // 로비 채팅 채널 구독 시에만 참여자 Set에 추가
        if (StompDestinations.isLobbySubscription(destination)
                && userIdentifier != null
                && !WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) {

            String roomId = StompDestinations.extractLobbyCode(destination);

            redisTemplate.opsForSet().add(RedisKeys.userRoomKey(roomId), userIdentifier);
            log.info("로비 참여자 추가 - 로비: {}, 식별자: {}", roomId, userIdentifier);
        }
    }

    /**
     * 세션 속성에서 사용자 식별자를 안전하게 추출합니다.
     * 식별자가 없거나 세션 속성이 null인 경우 UNKNOWN_IDENTIFIER를 반환합니다.
     */
    private String extractUserIdentifier(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) return WebSocketHeaders.UNKNOWN_IDENTIFIER;
        Object identifier = sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        return identifier != null ? (String) identifier : WebSocketHeaders.UNKNOWN_IDENTIFIER;
    }
}