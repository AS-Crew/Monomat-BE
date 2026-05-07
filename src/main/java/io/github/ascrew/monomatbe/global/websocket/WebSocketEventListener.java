/*
 * WebSocket 연결 생명주기 이벤트를 처리하는 리스너.
 *
 * [책임]
 * - SUBSCRIBE 이벤트 중 로비 채팅 채널 구독을 감지하여 로비 입장 상태를 Redis에 저장
 * - DISCONNECT 이벤트 발생 시 wsSessionId로 lobbyCode를 역추적하여 자동 퇴장 처리
 * - ENTER / LEAVE / ENTER_FAILED 시스템 메시지 브로드캐스트
 *
 * [중요 설계]
 * 실제 입장 상태 저장은 enter_lobby.lua에서 원자적으로 처리한다.
 * Java 코드에서 participants Set, order List, ws:connection Hash를 각각 따로 저장하면
 * 중간 실패 시 Redis 상태가 불일치할 수 있기 때문이다.
 *
 * [동일 userIdentifier 다중 세션 정책]
 * 같은 userIdentifier가 같은 로비에 다시 입장하면 최신 wsSessionId를 현재 유효 세션으로 본다.
 * 이전 wsSessionId는 stale 세션으로 간주하며, stale 세션의 DISCONNECT는 실제 퇴장으로 처리하지 않는다.
 *
 * [실패 처리 정책]
 * STOMP SUBSCRIBE 프레임은 이미 처리되어 클라이언트가 구독 상태가 될 수 있다.
 * 따라서 Redis Lua 입장 처리가 실패하면 클라이언트는 구독되어 있지만
 * 서버 입장 상태가 누락되는 불일치가 발생할 수 있다.
 *
 * 이를 방지하기 위해 Lua 실행 실패 또는 null 반환 시 재시도하고,
 * 최종 실패 시 ENTER_FAILED 메시지를 클라이언트에게 직접 전송한다.
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class WebSocketEventListener {

    // =========================================================
    // Redis Lua 반환값 상수
    // =========================================================

    /**
     * 신규 입장자가 정상적으로 추가된 경우.
     * participants Set에 새로 들어갔고, order List에도 추가된 상태.
     */
    private static final String ENTER_RESULT_ENTERED = "ENTERED";

    /**
     * 이미 로비 participants Set에 존재하던 사용자가 다시 구독한 경우.
     * order List에는 중복 저장하지 않고, ws:connection 매핑만 갱신한다.
     */
    private static final String ENTER_RESULT_ALREADY_JOINED = "ALREADY_JOINED";

    /**
     * 동일 userIdentifier의 기존 WebSocket 세션이 새 세션으로 교체된 경우.
     * 이전 wsSessionId는 stale 세션으로 간주한다.
     */
    private static final String ENTER_RESULT_SESSION_REPLACED_PREFIX = "SESSION_REPLACED:";

    /**
     * SESSION_REPLACED 반환값에서 이전 wsSessionId를 추출하기 위한 prefix 길이.
     */
    private static final int ENTER_RESULT_SESSION_REPLACED_PREFIX_LENGTH =
            ENTER_RESULT_SESSION_REPLACED_PREFIX.length();

    /**
     * Redis에 해당 lobby:{code} Hash가 존재하지 않는 경우.
     * 존재하지 않는 로비에 고스트 participants/order를 만들지 않기 위해 입장을 거부한다.
     */
    private static final String ENTER_RESULT_LOBBY_NOT_FOUND = "LOBBY_NOT_FOUND";

    // =========================================================
    // 실패 처리 상수
    // =========================================================

    /**
     * Lua 실행 최대 시도 횟수.
     *
     * 최초 실행 1회 + 재시도 1회다.
     * SUBSCRIBE 이벤트 처리를 과도하게 지연시키지 않기 위해 재시도는 1회로 제한한다.
     */
    private static final int ENTER_SCRIPT_MAX_ATTEMPTS = 2;

    /** Lua 결과가 null인 경우의 실패 사유. */
    private static final String ENTER_FAILURE_NULL_RESULT = "Lua result is null";

    /** Redis 또는 Lua 실행 중 예외가 발생한 경우의 실패 사유. */
    private static final String ENTER_FAILURE_EXCEPTION = "Lua execution exception";

    /** 예상하지 못한 Lua 반환값을 받은 경우의 실패 사유. */
    private static final String ENTER_FAILURE_UNKNOWN_RESULT = "Unknown Lua result";

    /** 존재하지 않는 로비 코드로 구독한 경우의 실패 메시지. */
    private static final String ENTER_FAILED_LOBBY_NOT_FOUND_MESSAGE =
            "존재하지 않는 로비입니다. 로비 목록을 새로고침해주세요.";

    /** 시스템 오류로 입장 처리가 실패한 경우의 실패 메시지. */
    private static final String ENTER_FAILED_SYSTEM_MESSAGE =
            "로비 입장 처리에 실패했습니다. 새로고침 후 다시 시도해주세요.";

    // =========================================================
    // 메시지/TTL 상수
    // =========================================================

    /** 입장 시스템 메시지 포맷. */
    private static final String ENTER_MESSAGE_FORMAT = "%s님이 입장하셨습니다.";

    /** 퇴장 시스템 메시지 포맷. */
    private static final String LEAVE_MESSAGE_FORMAT = "%s님이 퇴장하셨습니다.";

    /**
     * ws:connection:{wsSessionId} 및 lobby:{code}:user_session:{userIdentifier} 매핑 TTL.
     *
     * 정상 종료 시 DISCONNECT 이벤트에서 즉시 삭제한다.
     * TTL은 서버 장애, 이벤트 누락, 비정상 종료에 대비한 안전장치다.
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
     * ENTER/LEAVE 메시지를 로비 채팅 채널로 브로드캐스트할 때 사용한다.
     */
    private final RedisPublisher redisPublisher;

    /**
     * 현재 서버에 연결된 WebSocket 클라이언트에게 직접 메시지를 전송한다.
     *
     * ENTER_FAILED 메시지 또는 Pub/Sub 발행 실패 fallback 메시지는 Redis를 거치지 않고
     * 현재 서버의 Simple Broker로 직접 전송한다.
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
     */
    private final RedisScript<String> enterLobbyScript;

    /**
     * WebSocket 직접 전송 메시지 직렬화용 JsonMapper.
     *
     * ENTER_FAILED 또는 Pub/Sub fallback 메시지는 RedisSubscriber를 거치지 않고
     * 직접 SimpMessagingTemplate으로 전송되므로, DTO 객체를 그대로 보내지 않고
     * JSON 문자열로 변환하여 프론트 메시지 계약을 통일한다.
     */
    private final JsonMapper pubSubJsonMapper;

    public WebSocketEventListener(
            StringRedisTemplate stringRedisTemplate,
            RedisPublisher redisPublisher,
            SimpMessagingTemplate messagingTemplate,
            ApplicationEventPublisher eventPublisher,
            WebSocketMetric webSocketMetric,
            @Qualifier("enterLobbyScript") RedisScript<String> enterLobbyScript,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redisPublisher = redisPublisher;
        this.messagingTemplate = messagingTemplate;
        this.eventPublisher = eventPublisher;
        this.webSocketMetric = webSocketMetric;
        this.enterLobbyScript = enterLobbyScript;
        this.pubSubJsonMapper = pubSubJsonMapper;
    }

    /**
     * WebSocket 채널 구독 이벤트 처리.
     *
     * [중요]
     * 로비 관련 채널은 다음처럼 나뉜다.
     * - /topic/lobby/{code}         : 로비 채팅 채널
     * - /topic/lobby/{code}/refresh : 로비 정보 새로고침 채널
     *
     * 입장 처리는 반드시 채팅 채널 구독 시점에만 실행한다.
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

        // 세션 속성에도 현재 로비 코드를 저장한다.
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

            deleteStaleConnectionKey(wsSessionId);
            webSocketMetric.decrement();
            return;
        }

        log.info("WebSocket 연결 해제 - 식별자: {}, 로비: {}", userIdentifier, lobbyCode);

        if (lobbyCode != null) {
            // domain/lobby 서비스가 퇴장 Lua 스크립트를 실행하도록 이벤트 발행
            eventPublisher.publishEvent(new PlayerLeaveEvent(lobbyCode, userIdentifier));

            // 로비 채팅 채널에 퇴장 시스템 메시지 발행
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
     * - lobby:{code}:user_session:{userIdentifier}
     *
     * [실패 처리]
     * SUBSCRIBE 프레임은 이미 처리되어 클라이언트가 구독 상태가 될 수 있다.
     * 따라서 Lua 실행 실패, null 반환, 알 수 없는 반환값이 발생하면
     * ENTER_FAILED 메시지를 전송하여 클라이언트가 재시도 또는 로비 이탈을 수행할 수 있게 한다.
     */
    private void processLobbyEnter(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId
    ) {
        EnterLobbyExecutionResult executionResult =
                executeEnterLobbyScriptWithRetry(lobbyCode, userIdentifier, wsSessionId);

        String result = executionResult.result();

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

        if (result != null && result.startsWith(ENTER_RESULT_SESSION_REPLACED_PREFIX)) {
            String previousWsSessionId = result.substring(ENTER_RESULT_SESSION_REPLACED_PREFIX_LENGTH);

            log.info("로비 세션 교체 처리 - 로비: {}, 식별자: {}, previousWsSessionId: {}, currentWsSessionId: {}",
                    lobbyCode, userIdentifier, previousWsSessionId, wsSessionId);

            cleanupWsConnection(previousWsSessionId);
            return;
        }

        if (ENTER_RESULT_LOBBY_NOT_FOUND.equals(result)) {
            log.warn("로비 입장 거부 - 존재하지 않는 로비입니다. 로비: {}, 식별자: {}, wsSessionId: {}",
                    lobbyCode, userIdentifier, wsSessionId);

            publishEnterFailedMessage(lobbyCode, userIdentifier, ENTER_FAILED_LOBBY_NOT_FOUND_MESSAGE);
            cleanupWsConnection(wsSessionId);
            return;
        }

        handleLobbyEnterFailure(
                lobbyCode,
                userIdentifier,
                wsSessionId,
                executionResult.failureReason() != null
                        ? executionResult.failureReason()
                        : ENTER_FAILURE_UNKNOWN_RESULT,
                result,
                executionResult.exception()
        );
    }

    /**
     * enter_lobby.lua를 최대 ENTER_SCRIPT_MAX_ATTEMPTS만큼 실행한다.
     *
     * [재시도 대상]
     * - Redis 예외 발생
     * - Lua 결과가 null
     *
     * [재시도하지 않는 대상]
     * - ENTERED
     * - ALREADY_JOINED
     * - SESSION_REPLACED:{previousWsSessionId}
     * - LOBBY_NOT_FOUND
     *
     * LOBBY_NOT_FOUND는 일시 장애라기보다 잘못된 로비 코드 또는 이미 삭제된 로비 상태이므로
     * 재시도하지 않고 즉시 반환한다.
     */
    private EnterLobbyExecutionResult executeEnterLobbyScriptWithRetry(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId
    ) {
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= ENTER_SCRIPT_MAX_ATTEMPTS; attempt++) {
            try {
                String result = executeEnterLobbyScript(lobbyCode, userIdentifier, wsSessionId);

                if (result != null) {
                    return EnterLobbyExecutionResult.success(result);
                }

                log.warn("로비 입장 Lua 결과 null - 재시도 여부 확인. attempt: {}/{}, 로비: {}, 식별자: {}, wsSessionId: {}",
                        attempt, ENTER_SCRIPT_MAX_ATTEMPTS, lobbyCode, userIdentifier, wsSessionId);

            } catch (RuntimeException e) {
                lastException = e;
                log.warn("로비 입장 Lua 실행 예외 - 재시도 여부 확인. attempt: {}/{}, 로비: {}, 식별자: {}, wsSessionId: {}, message: {}",
                        attempt, ENTER_SCRIPT_MAX_ATTEMPTS, lobbyCode, userIdentifier, wsSessionId, e.getMessage());
            }
        }

        if (lastException != null) {
            return EnterLobbyExecutionResult.failure(ENTER_FAILURE_EXCEPTION, lastException);
        }

        return EnterLobbyExecutionResult.failure(ENTER_FAILURE_NULL_RESULT, null);
    }

    /**
     * enter_lobby.lua를 1회 실행한다.
     *
     * 재시도 정책은 executeEnterLobbyScriptWithRetry()에서만 관리한다.
     */
    private String executeEnterLobbyScript(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId
    ) {
        List<String> keys = List.of(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                RedisKeys.lobbyOrderKey(lobbyCode),
                RedisKeys.wsConnectionKey(wsSessionId),
                RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier)
        );

        return stringRedisTemplate.execute(
                enterLobbyScript,
                keys,
                userIdentifier,
                lobbyCode,
                String.valueOf(WS_CONNECTION_TTL.toMillis()),
                WebSocketHeaders.SESSION_USER_ID,
                WebSocketHeaders.SESSION_LOBBY_CODE,
                wsSessionId
        );
    }

    /**
     * 로비 입장 처리 최종 실패를 처리한다.
     *
     * [처리 내용]
     * - ERROR 로그 기록
     * - ENTER_FAILED 메시지 전송
     * - ws:connection 보상 삭제
     *
     * SUBSCRIBE 자체는 이미 완료되었을 수 있으므로,
     * 클라이언트는 ENTER_FAILED 메시지를 수신하면 로비 화면에서 이탈하거나 재시도해야 한다.
     */
    private void handleLobbyEnterFailure(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId,
            String reason,
            String result,
            Exception exception
    ) {
        if (exception == null) {
            log.error("로비 입장 처리 최종 실패 - reason: {}, result: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                    reason, result, lobbyCode, userIdentifier, wsSessionId);
        } else {
            log.error("로비 입장 처리 최종 실패 - reason: {}, result: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                    reason, result, lobbyCode, userIdentifier, wsSessionId, exception);
        }

        publishEnterFailedMessage(lobbyCode, userIdentifier, ENTER_FAILED_SYSTEM_MESSAGE);
        cleanupWsConnection(wsSessionId);
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
     *
     * 동일 userIdentifier가 같은 로비에 재연결된 경우,
     * 이전 wsSessionId는 stale 세션으로 간주한다.
     * stale 세션의 DISCONNECT는 participants/order를 제거하면 안 된다.
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
     * 로비 입장 실패 메시지를 해당 로비 채널로 전송한다.
     *
     * Redis 장애 때문에 enter_lobby.lua가 실패했을 수 있으므로
     * 실패 알림은 Redis Pub/Sub을 거치지 않고 현재 서버의 WebSocket Broker로 직접 전송한다.
     *
     * 단, 프론트 메시지 계약을 유지하기 위해 DTO 객체가 아니라 JSON 문자열로 전송한다.
     */
    private void publishEnterFailedMessage(
            String lobbyCode,
            String userIdentifier,
            String content
    ) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.ENTER_FAILED)
                .roomId(lobbyCode)
                .sender(userIdentifier)
                .content(content)
                .timestamp(LocalDateTime.now().toString())
                .build();

        sendDirectJsonMessage(
                StompDestinations.subscribeLobbyChat(lobbyCode),
                message,
                "ENTER_FAILED"
        );
    }

    /**
     * 현재 서버의 WebSocket Broker로 JSON 문자열 메시지를 직접 전송한다.
     *
     * Redis Pub/Sub 장애 또는 ENTER_FAILED처럼 Redis를 거치면 안 되는 상황에서 사용한다.
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
     *
     * participants/order가 변경되었으므로,
     * 로비 화면을 보고 있는 클라이언트가 최신 참여자 목록을 다시 조회하도록 유도한다.
     */
    private void notifyLobbyInfoRefresh(String lobbyCode) {
        messagingTemplate.convertAndSend(
                StompDestinations.subscribeLobbyRefresh(lobbyCode),
                StompDestinations.MSG_REFRESH_LOBBY_INFO
        );
    }

    /**
     * 입장 처리 실패 또는 세션 교체 시 ws:connection 키를 보상 삭제한다.
     */
    private void cleanupWsConnection(String wsSessionId) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            return;
        }

        try {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        } catch (Exception e) {
            log.error("로비 입장 실패 후 ws:connection 보상 삭제 실패 - wsSessionId: {}", wsSessionId, e);
        }
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
     * 현재 유효 세션의 DISCONNECT에서만 user_status와 lobbyUserSessionKey를 삭제한다.
     * stale 세션은 deleteStaleConnectionKey()에서 ws:connection만 삭제한다.
     */
    private void deleteDisconnectKeys(
            String userIdentifier,
            String wsSessionId,
            String lobbyCode
    ) {
        stringRedisTemplate.delete(RedisKeys.userStatusKey(userIdentifier));

        if (wsSessionId != null && !wsSessionId.isBlank()) {
            stringRedisTemplate.delete(RedisKeys.wsConnectionKey(wsSessionId));
        }

        if (lobbyCode != null && !lobbyCode.isBlank()) {
            stringRedisTemplate.delete(RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier));
        }
    }

    /**
     * enter_lobby.lua 실행 결과를 담는 내부 전용 record.
     *
     * result가 null이면 실패로 간주한다.
     * 실패 시 failureReason과 exception을 함께 보관하여 최종 로그에 남긴다.
     */
    private record EnterLobbyExecutionResult(
            String result,
            String failureReason,
            Exception exception
    ) {
        private static EnterLobbyExecutionResult success(String result) {
            return new EnterLobbyExecutionResult(result, null, null);
        }

        private static EnterLobbyExecutionResult failure(String failureReason, Exception exception) {
            return new EnterLobbyExecutionResult(null, failureReason, exception);
        }
    }
}