/*
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 * (연결 / 구독 / 연결 해제)
 *
 * [의존 방향]
 * global/websocket/WebSocketEventListener
 *         ↓ publishEvent(PlayerLeaveEvent)
 * Spring ApplicationEventPublisher
 *         ↓ 이벤트 전달
 * domain/lobby/service/LobbyEventService (@EventListener)
 */
package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;

/**
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    /** 퇴장 메시지 포맷. userIdentifier가 삽입됩니다. */
    private static final String LEAVE_MESSAGE_FORMAT = "%s님이 퇴장하셨습니다.";

    // [수정] RedisTemplate<String, Object>(JSON 직렬화) → StringRedisTemplate(순수 문자열)
    // Lua 스크립트(SREM, LREM, user_status 조회)와 포맷 일치를 보장합니다.
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisPublisher redisPublisher;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * WebSocket 연결 해제 이벤트 처리 (단일 진입점).
     *
     * [처리 순서]
     * 1. wsSessionId로 Redis에서 lobbyCode 역추적
     * 2. PlayerLeaveEvent 발행 → LobbyEventService가 @EventListener로 수신하여 퇴장 처리
     * 3. LEAVE 메시지 브로드캐스트
     * 4. Redis 키 정리 (user_status, ws:connection)
     *
     * [수정 — StringRedisTemplate 사용]
     * ws:connection Hash는 LobbyEventService.saveConnectionInfo()에서
     * StringRedisTemplate으로 저장되므로, 조회/삭제도 stringRedisTemplate을 사용합니다.
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

        // 1. Redis에서 세션 매핑 정보 역추적 (wsSessionId → lobbyCode)
        String lobbyCode = null;
        if (wsSessionId != null) {
            // [수정] stringRedisTemplate 사용 — saveConnectionInfo()와 동일한 직렬화 포맷
            Map<Object, Object> connectionInfo = stringRedisTemplate.opsForHash()
                    .entries(RedisKeys.wsConnectionKey(wsSessionId));
            if (!connectionInfo.isEmpty()) {
                lobbyCode = (String) connectionInfo.get(WebSocketHeaders.SESSION_LOBBY_CODE);
            }
        }

        log.info("WebSocket 연결 해제 - 식별자: {}, 로비: {}", userIdentifier, lobbyCode);

        // 2. PlayerLeaveEvent 발행
        if (lobbyCode != null) {
            eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));
        }

        // 3. LEAVE 메시지 브로드캐스트
        if (lobbyCode != null) {
            redisPublisher.publish(
                    StompDestinations.subscribeLobbyChat(lobbyCode),
                    ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.LEAVE)
                            .roomId(lobbyCode)
                            .sender(userIdentifier)
                            .content(String.format(LEAVE_MESSAGE_FORMAT, userIdentifier))
                            .build()
            );
        }

        // 4. Redis 키 정리
        // [수정] stringRedisTemplate 사용 — 저장 시와 동일한 클라이언트로 삭제
        stringRedisTemplate.delete(RedisKeys.userStatusKey(userIdentifier));
        if (wsSessionId != null) {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }
    }

    /**
     * WebSocket 채널 구독 이벤트 처리.
     *
     * [수정 — StringRedisTemplate + order List 추가]
     *
     * 기존: redisTemplate(JSON 직렬화)으로 participants Set에만 추가
     *       → Lua 스크립트의 SREM이 JSON 인코딩된 값과 불일치하여 퇴장 실패
     *
     * 수정: stringRedisTemplate(순수 문자열)으로 participants Set + order List 모두 추가
     *       → Lua 스크립트(create_lobby.lua, leave_lobby.lua)와 동일한 포맷 보장
     *
     * [participants와 order의 단일 관리 지점]
     * create_lobby.lua에서 방장을 SADD/RPUSH로 추가한 이후,
     * 이후 입장하는 참여자들은 이 이벤트 핸들러에서 동일한 키에 추가합니다.
     * leave_lobby.lua의 SREM/LREM이 양쪽 모두에서 정상 동작하려면
     * 저장 포맷이 순수 문자열이어야 합니다.
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

            // [수정] stringRedisTemplate 사용 — Lua 스크립트와 동일한 순수 문자열 포맷
            // participants Set: leave_lobby.lua의 SREM과 포맷 일치
            stringRedisTemplate.opsForSet().add(
                    RedisKeys.lobbyParticipantsKey(roomId),
                    userIdentifier
            );
            // order List: leave_lobby.lua의 LREM과 포맷 일치
            stringRedisTemplate.opsForList().rightPush(
                    RedisKeys.lobbyOrderKey(roomId),
                    userIdentifier
            );

            log.info("로비 참여자 추가 - 로비: {}, 식별자: {}", roomId, userIdentifier);
        }
    }
}
