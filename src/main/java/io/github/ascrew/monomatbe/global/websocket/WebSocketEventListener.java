/*
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 *
 * [책임]
 * - SUBSCRIBE 성공 이후 로비 입장 후처리 수행
 * - DISCONNECT 이벤트 발생 시 wsSessionId로 lobbyCode를 역추적하여 자동 퇴장 처리
 * - ENTER / LEAVE 시스템 메시지 브로드캐스트
 *
 * [중요 설계]
 * 실제 입장 상태 저장은 StompChannelInterceptor의 SUBSCRIBE preSend 단계에서
 * enter_lobby.lua로 먼저 확정한다.
 *
 * SessionSubscribeEvent는 구독 요청이 처리된 이후 발생하므로,
 * 이 리스너에서는 Redis 입장 상태를 직접 만들지 않고,
 * 인터셉터가 세션에 저장한 입장 결과를 기반으로 ENTER 메시지와 refresh 신호만 처리한다.
 */
package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class WebSocketEventListener {

    // =========================================================
    // Redis Lua 반환값 상수
    // =========================================================

    /** 신규 입장 처리 완료 */
    private static final String ENTER_RESULT_ENTERED = "ENTERED";

    /** 이미 참여 중인 사용자의 중복 구독 */
    private static final String ENTER_RESULT_ALREADY_JOINED = "ALREADY_JOINED";

    /** 동일 userIdentifier의 기존 WebSocket 세션이 새 세션으로 교체된 경우 */
    private static final String ENTER_RESULT_SESSION_REPLACED_PREFIX = "SESSION_REPLACED:";

    /** 오래된 세션의 늦은 SUBSCRIBE 요청 */
    private static final String ENTER_RESULT_STALE_SESSION_PREFIX = "STALE_SESSION:";

    /** 유효하지 않은 sessionSequence */
    private static final String ENTER_RESULT_INVALID_SEQUENCE = "INVALID_SEQUENCE";

    /** SESSION_REPLACED 반환값에서 이전 wsSessionId를 추출하기 위한 prefix 길이 */
    private static final int ENTER_RESULT_SESSION_REPLACED_PREFIX_LENGTH =
            ENTER_RESULT_SESSION_REPLACED_PREFIX.length();

    // =========================================================
    // 메시지 상수
    // =========================================================

    /** 입장 시스템 메시지 포맷 */
    private static final String ENTER_MESSAGE_FORMAT = "%s님이 입장하셨습니다.";

    /** 퇴장 시스템 메시지 포맷 */
    private static final String LEAVE_MESSAGE_FORMAT = "%s님이 퇴장하셨습니다.";

    // =========================================================
    // 설정값
    // =========================================================

    /**
     * 사용자 온라인 상태 TTL.
     *
     * [기본값]
     * - PT2H: 2시간
     *
     * [설정 예시]
     * monomat.websocket.user-status.ttl=PT2H
     *
     * [설계 의도]
     * WebSocket DISCONNECT 이벤트 누락 또는 서버 비정상 종료 시
     * user_status 및 sessions Set이 Redis에 영구적으로 남지 않도록 TTL을 적용한다.
     */
    private final Duration userStatusTtl;

    // =========================================================
    // 의존성
    // =========================================================

    /**
     * 순수 문자열 Redis 작업 전용 Template.
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Redis Pub/Sub 기반 시스템 메시지 발행자.
     */
    private final RedisPublisher redisPublisher;

    /**
     * 현재 서버에 연결된 WebSocket 클라이언트에게 직접 메시지를 전송한다.
     *
     * Redis Pub/Sub 발행 실패 fallback에 사용한다.
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * DISCONNECT 시 domain/lobby 쪽 퇴장 처리를 실행하기 위한 이벤트 발행자.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 활성 WebSocket 세션 수 Prometheus 메트릭.
     */
    private final WebSocketMetric webSocketMetric;

    /**
     * WebSocket 직접 전송 메시지 직렬화용 JsonMapper.
     *
     * Pub/Sub fallback 메시지를 DTO 객체가 아니라 JSON 문자열로 전송하기 위해 사용한다.
     */
    private final JsonMapper pubSubJsonMapper;

    public WebSocketEventListener(
            StringRedisTemplate stringRedisTemplate,
            RedisPublisher redisPublisher,
            SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            WebSocketMetric webSocketMetric,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper,
            @Value("${monomat.websocket.user-status.ttl:PT2H}") Duration userStatusTtl
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisPublisher = redisPublisher;
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.webSocketMetric = webSocketMetric;
        this.pubSubJsonMapper = pubSubJsonMapper;
        this.userStatusTtl = userStatusTtl;
    }

    /**
     * WebSocket 채널 구독 성공 이벤트 처리.
     *
     * [중요]
     * enter_lobby.lua 실행은 이미 StompChannelInterceptor의 SUBSCRIBE preSend 단계에서 끝난 상태다.
     *
     * 이 메서드는 Redis 입장 상태를 만들지 않고,
     * 세션 속성에 저장된 입장 결과를 기반으로 ENTER 메시지와 refresh 신호만 발행한다.
     */
    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        String destination = accessor.getDestination();
        String wsSessionId = accessor.getSessionId();

        if (sessionAttributes == null) {
            log.warn("로비 입장 후처리 중단 - STOMP 세션 속성이 없습니다. destination: {}", destination);
            return;
        }

        String userIdentifier = (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER);

        if (!isValidLobbyEnterRequest(destination, userIdentifier, wsSessionId)) {
            return;
        }

        String lobbyCode = StompDestinations.extractLobbyCode(destination);
        Object resultValue = sessionAttributes.remove(WebSocketHeaders.LOBBY_ENTER_RESULT);

        if (!(resultValue instanceof String result)) {
            handleMissingLobbyEnterResult(lobbyCode, userIdentifier, wsSessionId);
            return;
        }

        processLobbyEnterResult(lobbyCode, userIdentifier, wsSessionId, result);
    }

    /**
     * WebSocket 연결 해제 이벤트 처리.
     *
     * [처리 순서]
     * 1. 세션에서 userIdentifier 조회
     * 2. wsSessionId로 Redis ws:connection:{wsSessionId} 조회
     * 3. stale 세션 여부 확인
     * 4. 현재 유효 세션이면 PlayerLeaveEvent 발행
     * 5. LEAVE 시스템 메시지 브로드캐스트
     * 6. Redis 키 정리
     * 7. WebSocket 활성 세션 메트릭 감소
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

        if (lobbyCode != null && isStaleLobbySession(lobbyCode, userIdentifier, wsSessionId)) {
            log.info("stale WebSocket 세션 연결 해제 감지 - 실제 퇴장 처리 생략. 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);

            removeUserOnlineSessionOnly(userIdentifier, wsSessionId);
            deleteStaleConnectionKey(wsSessionId);
            webSocketMetric.decrement();
            return;
        }

        log.info("WebSocket 연결 해제 - 식별자: {}, 로비: {}", userIdentifier, lobbyCode);

        if (lobbyCode != null) {
            eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));
            publishLeaveMessage(lobbyCode, userIdentifier);
        }

        deleteDisconnectKeys(userIdentifier, wsSessionId, lobbyCode);

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
            log.warn("로비 입장 후처리 중단 - 사용자 식별자가 없습니다. destination: {}", destination);
            return false;
        }

        if (wsSessionId == null || wsSessionId.isBlank()) {
            log.warn("로비 입장 후처리 중단 - wsSessionId가 없습니다. userIdentifier: {}", userIdentifier);
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
     * 인터셉터에서 확정한 로비 입장 결과를 후처리한다.
     */
    private void processLobbyEnterResult(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId,
            String result
    ) {
        if (ENTER_RESULT_ENTERED.equals(result)) {
            log.info("로비 입장 후처리 완료 - 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);

            publishEnterMessage(lobbyCode, userIdentifier);
            notifyLobbyInfoRefresh(lobbyCode);
            return;
        }

        if (ENTER_RESULT_ALREADY_JOINED.equals(result)) {
            log.info("로비 중복 구독 후처리 - ENTER 메시지 생략. 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);
            return;
        }

        if (result.startsWith(ENTER_RESULT_SESSION_REPLACED_PREFIX)) {
            String previousWsSessionId = result.substring(ENTER_RESULT_SESSION_REPLACED_PREFIX_LENGTH);

            log.info("로비 세션 교체 후처리 - 로비: {}, 식별자: {}, previousWsSessionId: {}, currentWsSessionId: {}",
                    lobbyCode, userIdentifier, previousWsSessionId, wsSessionId);
            return;
        }

        if (result.startsWith(ENTER_RESULT_STALE_SESSION_PREFIX)) {
            log.warn("stale 로비 세션 후처리 요청 수신 - 후처리 생략. 로비: {}, 식별자: {}, wsSessionId: {}, result: {}",
                    lobbyCode, userIdentifier, wsSessionId, result);
            return;
        }

        if (ENTER_RESULT_INVALID_SEQUENCE.equals(result)) {
            log.warn("유효하지 않은 sequence 결과 후처리 요청 수신 - 후처리 생략. 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);
            return;
        }

        handleUnknownLobbyEnterResult(lobbyCode, userIdentifier, wsSessionId, result);
    }

    /**
     * 로비 입장 후처리 결과가 세션 속성에 없는 경우의 fallback 처리.
     *
     * 정상 흐름에서는 StompChannelInterceptor가 SUBSCRIBE preSend 단계에서
     * enter_lobby.lua를 성공시킨 뒤 LOBBY_ENTER_RESULT를 세션 속성에 저장한다.
     *
     * 따라서 이 값이 없다는 것은 입장 실패라기보다 후처리 메타데이터 누락에 가깝다.
     * 이 상황에서 ENTER_FAILED를 보내면 이미 Redis에 입장 처리된 사용자를 실패로 오인시킬 수 있다.
     *
     * 안전한 fallback으로 로비 정보 refresh만 전송하여 클라이언트가 서버 상태를 다시 조회하게 한다.
     */
    private void handleMissingLobbyEnterResult(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId
    ) {
        log.error("로비 입장 후처리 fallback 실행 - 인터셉터 입장 결과가 없습니다. 로비: {}, 식별자: {}, wsSessionId: {}",
                lobbyCode, userIdentifier, wsSessionId);

        notifyLobbyInfoRefresh(lobbyCode);
    }

    /**
     * 알 수 없는 Lua 입장 결과가 후처리 단계까지 전달된 경우의 fallback 처리.
     *
     * StompChannelInterceptor에서 Lua 반환값을 enum parser로 검증하므로,
     * 정상적으로는 알 수 없는 결과가 여기까지 오면 안 된다.
     *
     * 다만 반환값 계약 변경 또는 예외적 상황에 대비해 ENTER_FAILED 대신 refresh를 전송한다.
     * ENTER 메시지는 중복 발행 위험이 있고, ENTER_FAILED는 실제 입장 완료 상태와 충돌할 수 있기 때문이다.
     */
    private void handleUnknownLobbyEnterResult(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId,
            String result
    ) {
        log.error("로비 입장 후처리 fallback 실행 - 알 수 없는 결과 수신. result: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                result, lobbyCode, userIdentifier, wsSessionId);

        notifyLobbyInfoRefresh(lobbyCode);
    }

    /**
     * wsSessionId 기준으로 Redis에 저장된 lobbyCode를 역추적한다.
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
     * 현재 DISCONNECT 된 wsSessionId가 로비 내 최신 유효 세션인지 확인한다.
     */
    private boolean isStaleLobbySession(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId
    ) {
        if (lobbyCode == null || userIdentifier == null || wsSessionId == null) {
            return false;
        }

        String currentWsSessionId = stringRedisTemplate.opsForValue()
                .get(RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier));

        return currentWsSessionId != null && !currentWsSessionId.equals(wsSessionId);
    }

    /**
     * 로비 채팅 채널에 ENTER 시스템 메시지를 발행한다.
     *
     * Redis Pub/Sub 발행 실패 시 현재 서버의 WebSocket Broker로 fallback 전송한다.
     */
    private void publishEnterMessage(String lobbyCode, String userIdentifier) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.ENTER)
                .roomId(lobbyCode)
                .sender(userIdentifier)
                .content(String.format(ENTER_MESSAGE_FORMAT, userIdentifier))
                .timestamp(LocalDateTime.now().toString())
                .build();

        boolean published = redisPublisher.publish(
                StompDestinations.subscribeLobbyChat(lobbyCode),
                message
        );

        if (!published) {
            log.error("ENTER 메시지 Pub/Sub 발행 실패 - 로컬 WebSocket fallback 전송. 로비: {}, 식별자: {}",
                    lobbyCode, userIdentifier);

            sendDirectJsonMessage(
                    StompDestinations.subscribeLobbyChat(lobbyCode),
                    message,
                    "ENTER_FALLBACK"
            );
        }
    }

    /**
     * 로비 채팅 채널에 LEAVE 시스템 메시지를 발행한다.
     *
     * Redis Pub/Sub 발행 실패 시 현재 서버의 WebSocket Broker로 fallback 전송한다.
     */
    private void publishLeaveMessage(String lobbyCode, String userIdentifier) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.LEAVE)
                .roomId(lobbyCode)
                .sender(userIdentifier)
                .content(String.format(LEAVE_MESSAGE_FORMAT, userIdentifier))
                .timestamp(LocalDateTime.now().toString())
                .build();

        boolean published = redisPublisher.publish(
                StompDestinations.subscribeLobbyChat(lobbyCode),
                message
        );

        if (!published) {
            log.error("LEAVE 메시지 Pub/Sub 발행 실패 - 로컬 WebSocket fallback 전송. 로비: {}, 식별자: {}",
                    lobbyCode, userIdentifier);

            sendDirectJsonMessage(
                    StompDestinations.subscribeLobbyChat(lobbyCode),
                    message,
                    "LEAVE_FALLBACK"
            );
        }
    }

    /**
     * 현재 서버의 WebSocket Broker로 JSON 문자열 메시지를 직접 전송한다.
     *
     * Redis Pub/Sub 장애 상황의 fallback에 사용한다.
     * DTO 객체를 그대로 보내면 타입 정보가 포함될 수 있으므로 반드시 JSON 문자열로 변환한다.
     */
    private void sendDirectJsonMessage(
            String destination,
            ChatMessageDto message,
            String context
    ) {
        try {
            String payload = pubSubJsonMapper.writeValueAsString(message);
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.error("WebSocket 직접 메시지 전송 실패 - context: {}, destination: {}, messageType: {}",
                    context,
                    destination,
                    message != null ? message.getType() : null,
                    e);
        }
    }

    /**
     * 로비 내부 정보 새로고침 신호를 브로드캐스트한다.
     */
    private void notifyLobbyInfoRefresh(String lobbyCode) {
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(lobbyCode),
                StompDestinations.MSG_REFRESH_LOBBY_INFO
        );
    }

    /**
     * stale 세션의 ws:connection 키만 삭제한다.
     *
     * stale 세션은 최신 유효 세션이 아니므로 user_status나 lobbyUserSessionKey를 삭제하면 안 된다.
     */
    private void deleteStaleConnectionKey(String wsSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            return;
        }

        stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
    }

    /**
     * DISCONNECT 이후 불필요한 Redis 키를 정리한다.
     *
     * [중요]
     * user_status:{userIdentifier}는 무조건 삭제하지 않는다.
     * 현재 wsSessionId를 user_status:{userIdentifier}:sessions Set에서 제거한 뒤,
     * 남은 세션이 없을 때만 사용자를 오프라인으로 처리한다.
     */
    private void deleteDisconnectKeys(
            String userIdentifier,
            String wsSessionId,
            String lobbyCode
    ) {
        deleteUserOnlineSession(userIdentifier, wsSessionId);

        if (wsSessionId != null && !wsSessionId.isBlank()) {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }

        if (lobbyCode != null && !lobbyCode.isBlank()) {
            stringRedisTemplate.delete(RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier));
            stringRedisTemplate.delete(RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, userIdentifier));
        }
    }

    /**
     * DISCONNECT 된 WebSocket 세션을 사용자 온라인 세션 Set에서 제거한다.
     *
     * 마지막 세션이 종료된 경우에만 user_status:{userIdentifier}를 삭제한다.
     * 아직 다른 WebSocket 세션이 남아 있다면 사용자는 온라인 상태로 유지한다.
     */
    private void deleteUserOnlineSession(String userIdentifier, String wsSessionId) {
        if (!hasValidUserIdentifier(userIdentifier)) {
            return;
        }

        String userStatusKey = RedisKeys.userStatusKey(userIdentifier);
        String userStatusSessionsKey = RedisKeys.userStatusSessionsKey(userIdentifier);

        removeUserOnlineSession(userStatusSessionsKey, wsSessionId);

        Long remainingSessionCount = stringRedisTemplate.opsForSet().size(userStatusSessionsKey);

        if (remainingSessionCount == null || remainingSessionCount == 0) {
            deleteUserOnlineStatus(userStatusKey, userStatusSessionsKey, userIdentifier, wsSessionId);
            return;
        }

        refreshUserOnlineStatusTtl(userStatusKey, userStatusSessionsKey);

        log.info("사용자 온라인 상태 유지 - 남은 WebSocket 세션 수: {}, userIdentifier: {}, disconnectedWsSessionId: {}",
                remainingSessionCount, userIdentifier, wsSessionId);
    }

    /**
     * stale WebSocket 세션을 사용자 온라인 세션 Set에서만 제거한다.
     *
     * stale 세션은 로비 기준 최신 유효 세션이 아니므로,
     * 로비 퇴장 처리와 user_status 삭제 판단은 수행하지 않는다.
     */
    private void removeUserOnlineSessionOnly(String userIdentifier, String wsSessionId) {
        if (!hasValidUserIdentifier(userIdentifier)) {
            return;
        }

        String userStatusSessionsKey = RedisKeys.userStatusSessionsKey(userIdentifier);
        removeUserOnlineSession(userStatusSessionsKey, wsSessionId);

        log.info("stale WebSocket 세션을 온라인 세션 Set에서 제거 - userIdentifier: {}, wsSessionId: {}",
                userIdentifier, wsSessionId);
    }

    /**
     * 사용자 온라인 세션 Set에서 특정 wsSessionId를 제거한다.
     */
    private void removeUserOnlineSession(String userStatusSessionsKey, String wsSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            return;
        }

        stringRedisTemplate.opsForSet().remove(userStatusSessionsKey, wsSessionId);
    }

    /**
     * 사용자 온라인 상태 관련 Redis 키를 삭제한다.
     */
    private void deleteUserOnlineStatus(
            String userStatusKey,
            String userStatusSessionsKey,
            String userIdentifier,
            String wsSessionId
    ) {
        stringRedisTemplate.delete(userStatusKey);
        stringRedisTemplate.delete(userStatusSessionsKey);

        log.info("사용자 온라인 상태 삭제 - 마지막 WebSocket 세션 종료. userIdentifier: {}, wsSessionId: {}",
                userIdentifier, wsSessionId);
    }

    /**
     * 사용자 온라인 상태 관련 Redis 키의 TTL을 갱신한다.
     *
     * 일부 세션만 종료된 경우에도 다른 세션이 살아 있으므로,
     * user_status와 sessions Set의 TTL을 다시 연장하여 온라인 상태가 조기 만료되지 않도록 한다.
     */
    private void refreshUserOnlineStatusTtl(String userStatusKey, String userStatusSessionsKey) {
        stringRedisTemplate.expire(userStatusKey, userStatusTtl);
        stringRedisTemplate.expire(userStatusSessionsKey, userStatusTtl);
    }
}