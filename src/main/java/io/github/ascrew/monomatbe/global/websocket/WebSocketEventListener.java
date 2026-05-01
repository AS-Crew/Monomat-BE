/*
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 * (연결 / 구독 / 연결 해제)
 *
 * [리팩토링 변경 사항 — 의존 방향 역전 해결]
 * 기존: WebSocketEventListener(global) → LobbyEventService(domain) 직접 참조
 *       global이 domain을 직접 참조하는 의존 방향 역전 문제 존재
 *
 * 변경: WebSocketEventListener(global) → ApplicationEventPublisher로 이벤트 발행
 *       LobbyEventService(domain)가 @EventListener로 이벤트 수신
 *       global은 domain을 전혀 알 필요 없어짐
 *
 * [의존 방향]
 * global/websocket/WebSocketEventListener
 *         ↓ publishEvent(PlayerLeaveEvent)
 * Spring ApplicationEventPublisher
 *         ↓ 이벤트 전달
 * domain/lobby/service/LobbyEventService (@EventListener)
 */
package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    // 연결이 끊기면 handleDisconnectEvent에서 즉시 삭제하므로
    // TTL은 비정상 종료(서버 다운 등) 시 Redis 좀비 키 방지용
    private static final long USER_STATUS_TTL_HOURS = 2;

    private final RedisPublisher redisPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketMetric webSocketMetric;

    // [수정] LobbyEventService 직접 참조 제거
    //        → ApplicationEventPublisher로 교체하여 domain 의존성 완전 제거
    private final ApplicationEventPublisher eventPublisher;

    /**
     * WebSocket 연결 성공 이벤트 처리.
     *
     * [처리 내용]
     * 1. 세션에서 사용자 식별자 추출
     * 2. Redis에 온라인 상태 저장 (TTL 2시간)
     * 3. 활성 세션 카운터 증가 (Prometheus 메트릭)
     */
    @EventListener
    public void handleConnectEvent(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        // WebSocketSessionUtils 정적 메서드로 식별자 추출 (중복 로직 제거)
        String userIdentifier = WebSocketSessionUtils.extractUserIdentifier(sessionAttributes);

        if (WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier)) {
            log.warn("인증되지 않은 세션 연결 감지");
            return;
        }

        // 온라인 상태를 Redis에 저장
        // TTL은 비정상 종료 시 자동 만료 보호용 (정상 종료 시 handleDisconnectEvent에서 즉시 삭제)
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
     * [처리 순서]
     * 1. wsSessionId로 Redis에서 lobbyCode 역추적
     * 2. PlayerLeaveEvent 발행 → LobbyEventService가 수신하여 퇴장 처리
     * 3. LEAVE 메시지 브로드캐스트
     * 4. Redis 키 정리 (user_status, ws:connection)
     *
     * [수정 — 의존 방향 역전 해결]
     * 기존: lobbyEventService.handlePlayerLeave(lobbyCode, userIdentifier) 직접 호출
     *       → global이 domain을 직접 참조하는 의존 방향 역전
     *
     * 변경: eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier))
     *       → global은 이벤트 발행만 담당, domain이 수신하여 처리
     *       → WebSocketEventListener가 LobbyEventService를 전혀 알 필요 없어짐
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

        // 2. [수정] 직접 호출 → 이벤트 발행으로 교체
        //    PlayerLeaveEvent를 발행하면 Spring이 @EventListener를 가진
        //    LobbyEventService.handlePlayerLeave(PlayerLeaveEvent)로 전달합니다.
        //    WebSocketEventListener는 LobbyEventService를 전혀 알 필요가 없습니다.
        if (lobbyCode != null) {
            eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));
        }

        // 3. LEAVE 메시지 브로드캐스트
        //    채팅 알림은 로비 도메인 로직이 아닌 WebSocket 인프라 책임이므로
        //    이 클래스에서 직접 처리합니다.
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

        webSocketMetric.decrement();
    }

    /**
     * WebSocket 채널 구독 이벤트 처리.
     *
     * 로비 채널 구독 시 Redis 참여자 Set에 사용자를 추가합니다.
     *
     * [참여자 키 단일화]
     * lobby:{code}:participants를 단일 진실의 원천으로 사용합니다.
     * Lua 스크립트(leave_lobby.lua)가 퇴장 시 이 키에서 SREM으로 제거하므로
     * 입장 시에도 동일한 키에 추가하여 일관성을 보장합니다.
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

            // lobby:{code}:participants에 추가
            // Lua 스크립트가 퇴장 시 삭제하는 키와 동일하게 맞춰 일관성 보장
            redisTemplate.opsForSet().add(
                    RedisKeys.lobbyParticipantsKey(roomId),
                    userIdentifier
            );

            log.info("로비 참여자 추가 - 로비: {}, 식별자: {}", roomId, userIdentifier);
        }
    }
}