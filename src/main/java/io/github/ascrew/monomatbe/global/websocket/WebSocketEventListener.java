/*
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 *
 * [책임]
 * - SUBSCRIBE 이벤트 중 로비 채팅 채널 구독을 감지하여 로비 입장 상태를 Redis에 저장
 * - DISCONNECT 이벤트 발생 시 wsSessionId로 lobbyCode를 역추적하여 자동 퇴장 처리
 * - ENTER / LEAVE 시스템 메시지 브로드캐스트
 *
 * [중요 설계]
 * 실제 입장 상태 저장은 enter_lobby.lua에서 원자적으로 처리한다.
 * Java 코드에서 participants Set, order List, ws:connection Hash를 각각 따로 저장하면
 * 중간 실패 시 Redis 상태가 불일치할 수 있기 때문이다.
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    // =========================================================
    // Redis Lua 반환값 상수
    // =========================================================

    /**
     * 신규 입장자가 정상적으로 추가된 경우.
     * participants Set에 새로 들어갔고, order List에도 추가된 상태
     */
    private static final String ENTER_RESULT_ENTERED = "ENTERED";

    /**
     * 이미 로비 participants Set에 존재하던 사용자가 다시 구독한 경우.
     * order List에는 중복 저장하지 않고, ws:connection 매핑만 갱신
     */
    private static final String ENTER_RESULT_ALREADY_JOINED = "ALREADY_JOINED";

    /**
     * Redis에 해당 lobby:{code} Hash가 존재하지 않는 경우.
     * 존재하지 않는 로비에 고스트 participants/order를 만들지 않기 위해 입장을 거부한다.
     */
    private static final String ENTER_RESULT_LOBBY_NOT_FOUND = "LOBBY_NOT_FOUND";

    // =========================================================
    // 메시지/TTL 상수
    // =========================================================

    /** 입장 시스템 메시지 포맷. */
    private static final String ENTER_MESSAGE_FORMAT = "%s님이 입장하셨습니다.";

    /** 퇴장 시스템 메시지 포맷. */
    private static final String LEAVE_MESSAGE_FORMAT = "%s님이 퇴장하셨습니다.";

    /**
     * ws:connection:{wsSessionId} 매핑 TTL.
     *
     * 정상 종료 시 DISCONNECT 이벤트에서 즉시 삭제한다.
     * TTL은 서버 장애, 이벤트 누락, 비정상 종료에 대비한 안전장치
     */
    private static final Duration WS_CONNECTION_TTL = Duration.ofHours(2);

    // =========================================================
    // 의존성
    // =========================================================

    /**
     * 순수 문자열 Redis 작업 전용 Template.
     *
     * participants/order/ws:connection에는 문자열만 저장해야 한다.
     * RedisTemplate<String, Object>를 사용하면 JSON 직렬화로 따옴표가 포함될 수 있고,
     * leave_lobby.lua의 SREM/LREM 비교가 실패할 수 있다.
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Redis Pub/Sub 기반 시스템 메시지 발행자.
     * ENTER/LEAVE 메시지를 로비 채팅 채널로 브로드캐스트할 때 사용
     */
    private final RedisPublisher redisPublisher;

    /**
     * 로비 refresh 신호를 현재 서버에 연결된 WebSocket 클라이언트에게 전송한다.
     *
     * 기존 LobbyEventService도 SimpMessagingTemplate으로 refresh 신호를 보내고 있으므로,
     * 동일한 방식으로 로비 내부 갱신 신호를 전송한다.
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * DISCONNECT 시 domain/lobby 쪽 퇴장 처리를 실행하기 위한 이벤트 발행자.
     * global -> domain 직접 의존을 피하기 위해 Spring ApplicationEvent를 사용한다.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 활성 WebSocket 세션 수 Prometheus 메트릭.
     *
     * CONNECT 시 증가는 StompChannelInterceptor에서 처리하고,
     * DISCONNECT 시 감소는 실제 소켓 종료 이벤트를 받는 이 클래스에서 처리한다.
     */
    private final WebSocketMetric webSocketMetric;

    /**
     * 로비 입장 원자 처리 Lua 스크립트.
     *
     * RedisScript<String> Bean이 여러 개(create/leave/enter) 존재하므로
     * @Qualifier로 명확하게 주입
     */
    @Qualifier("enterLobbyScript")
    private final RedisScript<String> enterLobbyScript;

    /**
     * WebSocket 채널 구독 이벤트 처리.
     *
     * [중요]
     * 로비 관련 채널은 다음처럼 나뉩니다.
     * - /topic/lobby/{code}         : 로비 채팅 채널
     * - /topic/lobby/{code}/refresh : 로비 정보 새로고침 채널
     *
     * 입장 처리는 반드시 채팅 채널 구독 시점에만 실행합니다.
     * refresh 채널까지 입장 처리 대상으로 보면 같은 사용자가 participants/order에 중복 저장될 수 있다.
     */
    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        String destination = accessor.getDestination();
        String wsSessionId = accessor.getSessionId();

        if (sessionAttributes == null) {
            log.warn("로비 입장 처리 중단 - STOMP 세션 속성이 없습니다. destination: {}", destination);
            return;
        }

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);

        if (!isValidLobbyEnterRequest(destination, userIdentifier, wsSessionId)) {
            return;
        }

        String lobbyCode = StompDestinations.extractLobbyCode(destination);

        // 세션 속성에도 현재 로비 코드를 저장
        // 동일 연결 내 후속 메시지 처리에서 roomId를 빠르게 참조할 수 있다.
        sessionAttributes.put(WebSocketHeaders.ROOM_ID, lobbyCode);

        processLobbyEnter(lobbyCode, userIdentifier, wsSessionId);
    }

    /**
     * WebSocket 연결 해제 이벤트 처리.
     *
     * [처리 순서]
     * 1. 세션에서 userIdentifier 조회
     * 2. wsSessionId로 Redis ws:connection:{wsSessionId} 조회
     * 3. lobbyCode가 있으면 PlayerLeaveEvent 발행
     * 4. LEAVE 시스템 메시지 브로드캐스트
     * 5. user_status / ws:connection 키 정리
     * 6. WebSocket 활성 세션 메트릭 감소
     */
    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        String wsSessionId = accessor.getSessionId();

        if (sessionAttributes == null) {
            log.warn("WebSocket 연결 해제 처리 중단 - 세션 속성이 없습니다. wsSessionId: {}", wsSessionId);
            return;
        }

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);

        if (!hasValidUserIdentifier(userIdentifier)) {
            return;
        }

        String lobbyCode = findLobbyCodeByWsSessionId(wsSessionId);

        log.info("WebSocket 연결 해제 - 식별자: {}, 로비: {}", userIdentifier, lobbyCode);

        if (lobbyCode != null) {
            // domain/lobby 서비스가 퇴장 Lua 스크립트를 실행하도록 이벤트 발행
            eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));

            // 로비 채팅 채널에 퇴장 시스템 메시지 발행
            publishLeaveMessage(lobbyCode, userIdentifier);
        }

        deleteDisconnectKeys(userIdentifier, wsSessionId);

        webSocketMetric.decrement();
    }

    /**
     * 로비 입장 요청으로 처리해도 되는 SUBSCRIBE 이벤트인지 검증한다.
     */
    private boolean isValidLobbyEnterRequest(
            String destination,
            String userIdentifier,
            String wsSessionId
    ) {
        if (!StompDestinations.isLobbyChatSubscription(destination)) {
            return false;
        }

        if (!hasValidUserIdentifier(userIdentifier)) {
            log.warn("로비 입장 처리 중단 - 사용자 식별자가 없습니다. destination: {}", destination);
            return false;
        }

        if (wsSessionId == null || wsSessionId.isBlank()) {
            log.warn("로비 입장 처리 중단 - wsSessionId가 없습니다. userIdentifier: {}", userIdentifier);
            return false;
        }

        return true;
    }

    /**
     * userIdentifier가 실제 인증된 사용자 식별자인지 확인한다.
     */
    private boolean hasValidUserIdentifier(String userIdentifier) {
        return userIdentifier != null
                && !userIdentifier.isBlank()
                && !WebSocketHeaders.UNKNOWN_IDENTIFIER.equals(userIdentifier);
    }

    /**
     * 로비 입장 상태를 Redis에 원자적으로 저장한다.
     *
     * [Redis 변경 대상]
     * - lobby:{code}:participants
     * - lobby:{code}:order
     * - ws:connection:{wsSessionId}
     *
     * [브로드캐스트]
     * 신규 입장인 경우에만 ENTER 메시지와 refresh 신호를 보낸다.
     * 이미 입장한 사용자의 중복 구독에는 불필요한 시스템 메시지를 보내지 않는다.
     */
    private void processLobbyEnter(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId
    ) {
        List<String> keys = List.of(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                RedisKeys.lobbyOrderKey(lobbyCode),
                RedisKeys.wsConnectionKey(wsSessionId)
        );

        String result = stringRedisTemplate.execute(
                enterLobbyScript,
                keys,
                userIdentifier,
                lobbyCode,
                String.valueOf(WS_CONNECTION_TTL.toMillis()),
                WebSocketHeaders.SESSION_USER_ID,
                WebSocketHeaders.SESSION_LOBBY_CODE
        );

        if (ENTER_RESULT_ENTERED.equals(result)) {
            log.info("로비 입장 처리 완료 - 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);

            publishEnterMessage(lobbyCode, userIdentifier);
            notifyLobbyInfoRefresh(lobbyCode);
            return;
        }

        if (ENTER_RESULT_ALREADY_JOINED.equals(result)) {
            log.info("로비 중복 구독 처리 - order 중복 저장 방지. 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);
            return;
        }

        if (ENTER_RESULT_LOBBY_NOT_FOUND.equals(result)) {
            log.warn("로비 입장 거부 - 존재하지 않는 로비입니다. 로비: {}, 식별자: {}",
                    lobbyCode, userIdentifier);
            return;
        }

        log.error("로비 입장 처리 실패 - 알 수 없는 Lua 반환값. 로비: {}, 식별자: {}, 반환값: {}",
                lobbyCode, userIdentifier, result);
    }

    /**
     * wsSessionId 기준으로 Redis에 저장된 lobbyCode를 역추적합니다.
     */
    private String findLobbyCodeByWsSessionId(String wsSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            return null;
        }

        Map<Object, Object> connectionInfo = stringRedisTemplate.opsForHash()
                .entries(RedisKeys.wsConnectionKey(wsSessionId));

        if (connectionInfo.isEmpty()) {
            return null;
        }

        Object lobbyCode = connectionInfo.get(WebSocketHeaders.SESSION_LOBBY_CODE);

        return lobbyCode instanceof String value && !value.isBlank()
                ? value
                : null;
    }

    /**
     * 로비 채팅 채널에 ENTER 시스템 메시지를 발행합니다.
     */
    private void publishEnterMessage(String lobbyCode, String userIdentifier) {
        redisPublisher.publish(
                StompDestinations.subscribeLobbyChat(lobbyCode),
                ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.ENTER)
                        .roomId(lobbyCode)
                        .sender(userIdentifier)
                        .content(String.format(ENTER_MESSAGE_FORMAT, userIdentifier))
                        .timestamp(LocalDateTime.now().toString())
                        .build()
        );
    }

    /**
     * 로비 채팅 채널에 LEAVE 시스템 메시지를 발행합니다.
     */
    private void publishLeaveMessage(String lobbyCode, String userIdentifier) {
        redisPublisher.publish(
                StompDestinations.subscribeLobbyChat(lobbyCode),
                ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.LEAVE)
                        .roomId(lobbyCode)
                        .sender(userIdentifier)
                        .content(String.format(LEAVE_MESSAGE_FORMAT, userIdentifier))
                        .timestamp(LocalDateTime.now().toString())
                        .build()
        );
    }

    /**
     * 로비 내부 정보 새로고침 신호를 브로드캐스트합니다.
     *
     * participants/order가 변경되었으므로,
     * 로비 화면을 보고 있는 클라이언트가 최신 참여자 목록을 다시 조회하도록 유도합니다.
     */
    private void notifyLobbyInfoRefresh(String lobbyCode) {
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(lobbyCode),
                StompDestinations.MSG_REFRESH_LOBBY_INFO
        );
    }

    /**
     * DISCONNECT 이후 불필요한 Redis 키를 정리합니다.
     */
    private void deleteDisconnectKeys(String userIdentifier, String wsSessionId) {
        stringRedisTemplate.delete(RedisKeys.userStatusKey(userIdentifier));

        if (wsSessionId != null && !wsSessionId.isBlank()) {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }
    }
}