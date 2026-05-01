package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyEventService;
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
    private final LobbyEventService lobbyEventService;

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
     * WebSocket 연결 해제 이벤트 처리 (단일 진입점).
     * 기존에 WebSocketEventListener와 LobbyConnectionListener로 분산되어 있던
     * 퇴장 처리 로직을 단일 진입점으로 통합하여 처리 순서를 보장합니다.
     *
     * [처리 순서]
     * 1. wsSessionId로 Redis에서 userIdentifier, lobbyCode 조회
     * 2. Lua 스크립트 기반 원자적 퇴장 처리 (LobbyEventService 위임)
     * 3. LEAVE 메시지 브로드캐스트
     * 4. Redis 키 정리 (user_status, ws:connection, user_room)
     */
    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) return;

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        String wsSessionId = accessor.getSessionId();

        if (userIdentifier == null
                || WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) return;

        // 1. Redis에서 세션 매핑 정보 조회 (wsSessionId → lobbyCode)
        String lobbyCode = null;
        if (wsSessionId != null) {
            Map<Object, Object> connectionInfo = redisTemplate.opsForHash()
                    .entries(RedisKeys.wsConnectionKey(wsSessionId));
            if (!connectionInfo.isEmpty()) {
                lobbyCode = (String) connectionInfo.get("lobbyCode");
            }
        }

        log.info("WebSocket 연결 해제 - 식별자: {}, 로비: {}", userIdentifier, lobbyCode);

        // 2. Lua 스크립트 기반 원자적 퇴장 처리
        if (lobbyCode != null) {
            lobbyEventService.handlePlayerLeave(lobbyCode, userIdentifier);
        }

        // 3. LEAVE 메시지 브로드캐스트
        if (lobbyCode != null) {
            redisPublisher.publish(
                    StompDestinations.subscribeLobbyChat(lobbyCode),
                    ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.LEAVE)
                            .roomId(lobbyCode)
                            .sender(userIdentifier)
                            .content(userIdentifier + "님이 퇴장하셨습니다.")
                            .build()
            );
        }

        // 4. Redis 키 정리
        redisTemplate.delete(RedisKeys.userStatusKey(userIdentifier));
        if (wsSessionId != null) {
            redisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }
        if (lobbyCode != null) {
            redisTemplate.opsForSet().remove(
                    RedisKeys.userRoomKey(lobbyCode), userIdentifier);
        }

        webSocketMetric.decrement();
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
        return identifier != null
                ? (String) identifier
                : WebSocketHeaders.UNKNOWN_IDENTIFIER;
    }
}