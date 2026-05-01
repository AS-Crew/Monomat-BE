/*
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 * (연결 / 구독 / 연결 해제)
 *
 * [리팩토링 변경 사항]
 * 1. extractUserIdentifier() private 메서드 제거
 *    → WebSocketSessionUtils.extractUserIdentifier()로 위임
 *    → ChatService에 동일 로직이 중복 존재하던 문제 해결
 *
 * 2. handleSubscribeEvent() — 참여자 키 이중 관리 제거
 *    기존: user_room:{lobbyCode} Set에 추가 (lobby:{code}:participants와 이중 관리)
 *    수정: lobby:{code}:participants에 직접 추가 (단일 진실의 원천 유지)
 *    → Lua 스크립트가 퇴장 시 삭제하는 키와 동일한 키를 입장 시에도 사용
 *
 * 3. handleDisconnectEvent() — user_room 키 정리 코드 제거
 *    기존: Lua 스크립트 이후 user_room 키를 Java에서 별도 삭제 (이중 관리)
 *    수정: participants 삭제는 Lua 스크립트가 원자적으로 처리하므로 Java 중복 삭제 제거
 */
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

    // 사용자 온라인 상태 TTL (단위: 시간)
    // 연결이 끊기면 handleDisconnectEvent에서 즉시 삭제하므로 TTL은 비정상 종료 시 보호용
    private static final long USER_STATUS_TTL_HOURS = 2;

    private final RedisPublisher redisPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketMetric webSocketMetric;
    private final LobbyEventService lobbyEventService;

    /**
     * WebSocket 연결 성공 이벤트 처리.
     *
     * [처리 내용]
     * 1. 세션에서 사용자 식별자 추출
     * 2. Redis에 온라인 상태 저장 (TTL 2시간)
     * 3. 활성 세션 카운터 증가 (Prometheus 메트릭)
     *
     * [리팩토링]
     * extractUserIdentifier() private 메서드 제거
     * → WebSocketSessionUtils.extractUserIdentifier()로 위임
     */
    @EventListener
    public void handleConnectEvent(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        // [수정] private extractUserIdentifier() → WebSocketSessionUtils 정적 메서드로 교체
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(sessionAttributes);

        if (WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) {
            log.warn("인증되지 않은 세션 연결 감지");
            return;
        }

        // 온라인 상태를 Redis에 저장 (비정상 종료 시 TTL이 자동 만료 보호)
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
     *
     * 기존에 WebSocketEventListener와 LobbyConnectionListener로 분산되어 있던
     * 퇴장 처리 로직을 단일 진입점으로 통합하여 처리 순서를 보장합니다.
     *
     * [처리 순서]
     * 1. wsSessionId로 Redis에서 userIdentifier, lobbyCode 역추적
     * 2. Lua 스크립트 기반 원자적 퇴장 처리 (LobbyEventService 위임)
     * 3. LEAVE 메시지 브로드캐스트
     * 4. Redis 키 정리 (user_status, ws:connection)
     *
     * [리팩토링]
     * user_room:{lobbyCode} Set 정리 코드 제거
     * → participants는 Lua 스크립트가 원자적으로 처리하므로 Java 중복 삭제 불필요
     */
    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes == null) return;

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);
        String wsSessionId = accessor.getSessionId();

        // 인증되지 않은 세션이거나 식별자 없으면 처리 불필요
        if (userIdentifier == null
                || WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) return;

        // 1. Redis에서 세션 매핑 정보 역추적 (wsSessionId → lobbyCode)
        //    LobbyEventService.saveConnectionInfo()에서 연결 시 저장한 데이터
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
        //    lobby:{code}:participants에서의 제거는 Lua 스크립트 내부에서 처리됩니다.
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
        // 온라인 상태 키 삭제
        redisTemplate.delete(RedisKeys.userStatusKey(userIdentifier));
        // WebSocket 세션 매핑 키 삭제
        if (wsSessionId != null) {
            redisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }

        // [삭제] user_room 키 정리 코드 제거
        // 기존: redisTemplate.opsForSet().remove(RedisKeys.userRoomKey(lobbyCode), userIdentifier);
        // 이유: lobby:{code}:participants를 단일 진실의 원천으로 통일하고
        //       user_room:{lobbyCode}의 이중 관리를 제거했습니다.
        //       participants 삭제는 Lua 스크립트(leave_lobby.lua)가 원자적으로 처리합니다.

        webSocketMetric.decrement();
    }

    /**
     * WebSocket 채널 구독 이벤트 처리.
     *
     * 로비 채널 구독 시 Redis 참여자 Set에 사용자를 추가합니다.
     *
     * [리팩토링 — 참여자 키 단일화]
     * 기존: user_room:{lobbyCode} Set에 추가
     *       → Lua 스크립트가 관리하는 lobby:{code}:participants와 이중 관리 문제
     *
     * 수정: lobby:{code}:participants에 직접 추가
     *       → Lua 스크립트가 퇴장 시 삭제하는 키와 동일하게 맞춰 일관성 보장
     *       → Redis에 참여자 정보를 저장하는 Set이 하나로 통일됨
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

            // [수정] user_room:{roomId} → lobby:{roomId}:participants로 변경
            //        Lua 스크립트가 퇴장 시 SREM으로 제거하는 키와 동일한 키에 추가하여
            //        입장/퇴장 모두 동일한 Set을 사용하는 일관성 보장
            redisTemplate.opsForSet().add(
                    RedisKeys.lobbyParticipantsKey(roomId),
                    userIdentifier
            );

            log.info("로비 참여자 추가 - 로비: {}, 식별자: {}", roomId, userIdentifier);
        }
    }
}