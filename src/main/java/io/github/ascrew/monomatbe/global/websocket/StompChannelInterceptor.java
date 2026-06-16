package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * STOMP 채널 인터셉터
 *
 * [책임]
 * - CONNECT 시 userIdentifier 검증 및 세션 저장
 * - CONNECT 시 Redis INCR 기반 sessionSequence 발급
 * - CONNECT 시 사용자 온라인 상태 저장
 * - SUBSCRIBE/SEND/UNSUBSCRIBE 시 인증 세션 검증
 * - 로비 채팅 채널 SUBSCRIBE 시 enter_lobby.lua를 먼저 실행하여 입장 상태 확정
 *
 * [중요]
 * SessionSubscribeEvent는 구독 요청이 처리된 이후 발생한다.
 * 따라서 해당 이벤트에서 Redis 입장 처리를 수행하면 Lua 실패 시에도 클라이언트는 이미 구독된 상태가 될 수 있다.
 *
 * 이를 방지하기 위해 /topic/lobby/{code} 구독은 preSend 단계에서
 * enter_lobby.lua를 먼저 실행하고, Redis 입장 상태가 확정된 경우에만 SUBSCRIBE를 통과시킨다.
 */
@Slf4j
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            );

    private static final String ENTER_FAILURE_NULL_RESULT = "Lua result is null";
    private static final String ENTER_FAILURE_EXCEPTION = "Lua execution exception";

    /**
     * ws:connection:{wsSessionId}, lobby:{code}:user_session:{userIdentifier},
     * lobby:{code}:user_session_seq:{userIdentifier} 매핑 TTL
     *
     * WebSocket DISCONNECT 이벤트 누락, 서버 비정상 종료에 대비한 안전장치다.
     *
     * 기존 2시간은 장시간 로비 대기 또는 게임 진행 중 TTL 만료 타이밍 이슈가 생길 수 있어 6시간으로 늘린다.
     *
     * 사용자 온라인 상태 TTL은 userStatusTtl 설정값을 사용한다.
     */
    private static final Duration WS_CONNECTION_TTL = Duration.ofHours(6);

    private final StringRedisTemplate stringRedisTemplate;
    private final WebSocketMetric webSocketMetric;
    private final RedisScript<String> enterLobbyScript;
    private final LobbyEnterResultMapper lobbyEnterResultMapper = new LobbyEnterResultMapper();

    /**
     * 사용자 온라인 상태 TTL
     *
     * [기본값]
     * - PT2H: 2시간
     *
     * [설정 예시]
     * monomat.websocket.user-status.ttl=PT2H
     *
     * [설계 의도]
     * 사용자 온라인 상태 생성 시점의 TTL 정책을 설정값으로 관리한다.
     * DISCONNECT에서는 TTL을 연장하지 않고 세션 제거 및 마지막 세션 여부만 판단한다.
     */
    private final Duration userStatusTtl;

    /**
     * 로비 입장 Lua 재시도 횟수
     *
     * 기본값은 2입니다.
     * 즉, 최초 실행 1회 + 재시도 1회를 의미한다.
     *
     * 설정 파일에 값을 추가하지 않아도 기본값 2로 동작합니다.
     * 운영 환경에서 조정이 필요하면 아래 키를 사용할 수 있습니다.
     *
     * monomat.websocket.lobby-enter.retry-attempts=3
     */
    @Value("${monomat.websocket.lobby-enter.retry-attempts:2}")
    private int lobbyEnterRetryAttempts;

    public StompChannelInterceptor(
            StringRedisTemplate stringRedisTemplate,
            WebSocketMetric webSocketMetric,
            @Qualifier("enterLobbyScript") RedisScript<String> enterLobbyScript,
            @Value("${monomat.websocket.user-status.ttl:PT2H}") Duration userStatusTtl
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.webSocketMetric = webSocketMetric;
        this.enterLobbyScript = enterLobbyScript;
        this.userStatusTtl = userStatusTtl;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        StompCommand command = accessor.getCommand();

        switch (command) {
            case CONNECT -> handleConnect(accessor, sessionAttributes);
            case SUBSCRIBE, SEND, UNSUBSCRIBE -> validateSession(accessor, sessionAttributes);
            case DISCONNECT -> handleDisconnect(sessionAttributes);
            default -> {
                // 별도 처리 불필요
            }
        }

        return message;
    }

    /**
     * CONNECT 명령 처리
     */
    private void handleConnect(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes
    ) {
        String userIdentifier = accessor.getFirstNativeHeader(WebSocketHeaders.USER_IDENTIFIER);

        if (userIdentifier == null || userIdentifier.isBlank()) {
            log.warn("STOMP CONNECT 거부: 사용자 식별자 없음");
            throw new StompErrorException(StompErrorCode.CONNECT_USER_IDENTIFIER_MISSING);
        }

        if (!UUID_PATTERN.matcher(userIdentifier).matches()) {
            log.warn("STOMP CONNECT 거부: 유효하지 않은 식별자 형식 = {}",
                    sanitizeForLog(userIdentifier));
            throw new StompErrorException(StompErrorCode.CONNECT_USER_IDENTIFIER_INVALID);
        }

        if (!isActiveAuthSession(userIdentifier)) {
            log.warn("STOMP CONNECT 거부: revoke되었거나 만료된 세션 - userIdentifier: {}",
                    sanitizeForLog(userIdentifier));
            throw new StompErrorException(StompErrorCode.CONNECT_SESSION_REVOKED);
        }

        Long sessionSequence = stringRedisTemplate.opsForValue()
                .increment(RedisKeys.WS_SESSION_SEQUENCE);

        if (sessionSequence == null) {
            throw new StompErrorException(StompErrorCode.CONNECT_SESSION_SEQUENCE_FAILED);
        }

        if (sessionAttributes != null) {
            sessionAttributes.put(WebSocketHeaders.USER_IDENTIFIER, userIdentifier);
            sessionAttributes.put(WebSocketHeaders.SESSION_SEQUENCE, sessionSequence);
        }

        String wsSessionId = accessor.getSessionId();

        if (wsSessionId == null || wsSessionId.isBlank()) {
            log.warn("STOMP CONNECT 거부: WebSocket 세션 ID 없음 - userIdentifier: {}", userIdentifier);
            throw new StompErrorException(StompErrorCode.CONNECT_WS_SESSION_ID_MISSING);
        }

        log.debug("STOMP CONNECT 온라인 상태 저장 시작 - userIdentifier: {}, wsSessionId: {}, sessionSequence: {}",
                userIdentifier, wsSessionId, sessionSequence);

        accessor.setUser(new StompPrincipal(userIdentifier));

        saveUserOnlineSession(userIdentifier, wsSessionId);

        webSocketMetric.increment();

        log.info("STOMP CONNECT 성공 - userIdentifier: {}, wsSessionId: {}, sessionSequence: {}",
                userIdentifier, wsSessionId, sessionSequence);
    }

    /**
     * 인증 세션이 여전히 활성 상태인지 검증한다. (#204)
     *
     * [목적]
     * 회원 중복 로그인 차단/force 재로그인 또는 로그아웃으로 revoke된 세션은
     * Redis active session 마커(auth:session:active:{id})가 삭제된다.
     * revoke된 세션 식별자로 들어오는 WebSocket (재)연결을 CONNECT 단계에서 차단한다.
     *
     * [fail-closed]
     * Redis 조회 실패 시 JwtAuthenticationFilter와 동일하게 인증을 신뢰하지 않고 연결을 거부한다.
     */
    private boolean isActiveAuthSession(String userIdentifier) {
        try {
            return Boolean.TRUE.equals(
                    stringRedisTemplate.hasKey(RedisKeys.activeSessionKey(userIdentifier))
            );
        } catch (RuntimeException e) {
            log.warn("STOMP CONNECT active session 조회 실패 - fail-closed 적용. userIdentifier: {}",
                    sanitizeForLog(userIdentifier), e);
            return false;
        }
    }

    /**
     * CONNECT 이후 명령에 대해 인증 세션 존재 여부를 검증한다.
     */
    private void validateSession(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes
    ) {
        String userIdentifier = sessionAttributes != null
                ? (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER)
                : null;

        if (userIdentifier == null) {
            log.warn("[{}] 인증되지 않은 세션 접근 차단", accessor.getCommand());
            throw new StompErrorException(StompErrorCode.SESSION_UNAUTHENTICATED);
        }

        // 중복 로그인/로그아웃으로 revoke된 세션이 이미 연결된 상태에서 SEND/SUBSCRIBE를 계속
        // 통과하는 것을 막는다. (#204) CONNECT 시점 검증만으로는 연결 이후 revoke를 차단할 수 없으므로
        // 실제 동작을 수반하는 SEND/SUBSCRIBE 처리 전에 active session 마커를 재검증한다.
        // UNSUBSCRIBE는 정리 동작이므로 재검증 대상에서 제외한다.
        StompCommand command = accessor.getCommand();
        if ((command == StompCommand.SUBSCRIBE || command == StompCommand.SEND)
                && !isActiveAuthSession(userIdentifier)) {
            log.warn("[{}] revoke된 세션 접근 차단 - userIdentifier: {}",
                    command, sanitizeForLog(userIdentifier));
            throw new StompErrorException(StompErrorCode.SESSION_REVOKED);
        }

        switch (accessor.getCommand()) {
            case SUBSCRIBE -> handleSubscribe(accessor, sessionAttributes, userIdentifier);
            case SEND -> log.info("[SEND] 메시지 발송 - 식별자: {}, 경로: {}",
                    userIdentifier, accessor.getDestination());
            case UNSUBSCRIBE -> log.info("[UNSUBSCRIBE] 구독 해제 - 식별자: {}", userIdentifier);
            default -> {
                // 별도 처리 불필요
            }
        }
    }

    /**
     * SUBSCRIBE 명령 처리
     */
    private void handleSubscribe(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes,
            String userIdentifier
    ) {
        String destination = accessor.getDestination();
        String wsSessionId = accessor.getSessionId();

        if (StompDestinations.isLobbyChatSubscription(destination)) {
            String lobbyCode = StompDestinations.extractLobbyCode(destination);

            if (sessionAttributes != null) {
                sessionAttributes.put(WebSocketHeaders.ROOM_ID, lobbyCode);
            }

            String enterResult = executeEnterLobbyBeforeSubscribe(
                    lobbyCode,
                    userIdentifier,
                    wsSessionId,
                    sessionAttributes
            );

            if (sessionAttributes != null) {
                sessionAttributes.put(WebSocketHeaders.LOBBY_ENTER_RESULT, enterResult);
            }

            log.info("[SUBSCRIBE] 로비 입장 확정 후 구독 허용 - 식별자: {}, 로비: {}, wsSessionId: {}, result: {}",
                    userIdentifier, lobbyCode, wsSessionId, enterResult);
            return;
        }

        if (StompDestinations.isLobbySubscription(destination)) {
            String roomId = StompDestinations.extractLobbyCode(destination);
            if (sessionAttributes != null) {
                sessionAttributes.put(WebSocketHeaders.ROOM_ID, roomId);
            }
        }

        log.info("[SUBSCRIBE] 구독 - 식별자: {}, 경로: {}", userIdentifier, destination);
    }

    /**
     * enter_lobby.lua를 SUBSCRIBE 통과 전에 실행한다.
     */
    private String executeEnterLobbyBeforeSubscribe(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId,
            Map<String, Object> sessionAttributes
    ) {
        if (wsSessionId == null || wsSessionId.isBlank()) {
            throw new StompErrorException(StompErrorCode.LOBBY_ENTER_WS_SESSION_MISSING);
        }

        long sessionSequence = extractSessionSequence(sessionAttributes);

        RuntimeException lastException = null;
        int maxAttempts = Math.max(1, lobbyEnterRetryAttempts);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String result = executeEnterLobbyScript(
                        lobbyCode,
                        userIdentifier,
                        wsSessionId,
                        sessionSequence
                );

                LobbyEnterResultMapper.LobbyEnterResultType resultType =
                        lobbyEnterResultMapper.parse(result);

                switch (resultType) {
                    case ENTERED, ALREADY_JOINED, SESSION_REPLACED -> {
                        return result;
                    }

                    case UNKNOWN -> {
                        if (result == null) {
                            log.warn("로비 입장 Lua 결과 null - 재시도 여부 확인. attempt: {}/{}, 로비: {}, 식별자: {}, wsSessionId: {}",
                                    attempt, maxAttempts, lobbyCode, userIdentifier, wsSessionId);
                            continue;
                        }

                        log.error("로비 입장 Lua 알 수 없는 반환값 - result: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                                result, lobbyCode, userIdentifier, wsSessionId);

                        throwLobbyEnterFailure(
                                wsSessionId,
                                resultType.resolveErrorCode()
                        );
                    }

                    default -> throwLobbyEnterFailure(
                            wsSessionId,
                            resultType.resolveErrorCode()
                    );
                }

            } catch (StompErrorException e) {
                throw e;
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("로비 입장 Lua 실행 예외 - 재시도 여부 확인. attempt: {}/{}, 로비: {}, 식별자: {}, wsSessionId: {}, message: {}",
                        attempt, maxAttempts, lobbyCode, userIdentifier, wsSessionId, e.getMessage());
            }
        }

        if (lastException != null) {
            log.error("로비 입장 Lua 최종 실패 - reason: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                    ENTER_FAILURE_EXCEPTION, lobbyCode, userIdentifier, wsSessionId, lastException);
        } else {
            log.error("로비 입장 Lua 최종 실패 - reason: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                    ENTER_FAILURE_NULL_RESULT, lobbyCode, userIdentifier, wsSessionId);
        }

        cleanupWsConnection(wsSessionId);
        throw new StompErrorException(StompErrorCode.LOBBY_ENTER_TEMPORARILY_UNAVAILABLE);
    }

    /**
     * STOMP 세션 속성에서 sessionSequence를 추출한다.
     */
    private long extractSessionSequence(Map<String, Object> sessionAttributes) {
        if (sessionAttributes == null) {
            throw new StompErrorException(StompErrorCode.LOBBY_ENTER_SESSION_ATTRIBUTES_MISSING);
        }

        Object value = sessionAttributes.get(WebSocketHeaders.SESSION_SEQUENCE);

        if (value instanceof Number number) {
            return number.longValue();
        }

        throw new StompErrorException(StompErrorCode.LOBBY_ENTER_SEQUENCE_MISSING);
    }

    /**
     * enter_lobby.lua를 1회 실행한다.
     *
     * [KEYS 계약]
     * KEYS[1] = lobby:{code}
     * KEYS[2] = lobby:{code}:participants
     * KEYS[3] = lobby:{code}:order
     * KEYS[4] = lobby:{code}:kicked
     * KEYS[5] = ws:connection:{wsSessionId}
     * KEYS[6] = lobby:{code}:user_session:{userIdentifier}
     * KEYS[7] = lobby:{code}:user_session_seq:{userIdentifier}
     * KEYS[8] = lobby:public:most_players
     * KEYS[9] = lobby:public:most_available
     */
    private String executeEnterLobbyScript(
            String lobbyCode,
            String userIdentifier,
            String wsSessionId,
            long sessionSequence
    ) {
        List<String> keys = List.of(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                RedisKeys.lobbyOrderKey(lobbyCode),
                RedisKeys.lobbyKickedKey(lobbyCode),
                RedisKeys.wsConnectionKey(wsSessionId),
                RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier),
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, userIdentifier),
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        return stringRedisTemplate.execute(
                enterLobbyScript,
                keys,
                userIdentifier,
                lobbyCode,
                String.valueOf(WS_CONNECTION_TTL.toMillis()),
                WebSocketHeaders.SESSION_USER_ID,
                WebSocketHeaders.SESSION_LOBBY_CODE,
                wsSessionId,
                String.valueOf(sessionSequence)
        );
    }

    /**
     * 로비 입장 실패 결과를 STOMP 표준 예외로 변환한다.
     *
     * Lua 실패 결과는 대부분 현재 ws:connection을 유지할 이유가 없으므로
     * 먼저 보상 삭제한 뒤, FE가 처리 가능한 StompErrorCode를 가진 예외를 던진다.
     */
    private void throwLobbyEnterFailure(
            String wsSessionId,
            StompErrorCode errorCode
    ) {
        cleanupWsConnection(wsSessionId);
        throw new StompErrorException(errorCode);
    }

    /**
     * Lua 실패 시 현재 ws:connection 키를 보상 삭제한다.
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

    private void handleDisconnect(Map<String, Object> sessionAttributes) {
        String userIdentifier = sessionAttributes != null
                ? (String) sessionAttributes.get(WebSocketHeaders.USER_IDENTIFIER)
                : WebSocketHeaders.UNKNOWN_IDENTIFIER;

        log.info("STOMP DISCONNECT: {}", userIdentifier);
    }

    private String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }

        String sanitized = value.replaceAll("[\\r\\n\\t]", "");
        return sanitized.length() > 50 ? sanitized.substring(0, 50) + "..." : sanitized;
    }

    /**
     * 사용자 온라인 상태와 현재 WebSocket 세션을 Redis에 저장한다.
     *
     * [저장 구조]
     * - user_status:{userIdentifier}:sessions = Set<wsSessionId>
     * - user_status:{userIdentifier} = ONLINE
     *
     * [중요]
     * 온라인 상태 TTL은 CONNECT 시점의 생존 신호를 기준으로 설정한다.
     * DISCONNECT는 세션 정리 이벤트이므로 TTL 연장 기준으로 사용하지 않는다.
     */
    private void saveUserOnlineSession(String userIdentifier, String wsSessionId) {
        String userStatusKey = RedisKeys.userStatusKey(userIdentifier);
        String userStatusSessionsKey = RedisKeys.userStatusSessionsKey(userIdentifier);

        try {
            stringRedisTemplate.opsForSet().add(userStatusSessionsKey, wsSessionId);
            stringRedisTemplate.expire(userStatusSessionsKey, userStatusTtl);

            stringRedisTemplate.opsForValue().set(
                    userStatusKey,
                    WebSocketHeaders.STATUS_ONLINE,
                    userStatusTtl
            );
        } catch (RuntimeException e) {
            log.error("STOMP CONNECT 온라인 상태 저장 실패 - userIdentifier: {}, wsSessionId: {}, userStatusKey: {}, userStatusSessionsKey: {}",
                    userIdentifier, wsSessionId, userStatusKey, userStatusSessionsKey, e);
            throw new StompErrorException(StompErrorCode.CONNECT_ONLINE_STATUS_FAILED, e);
        }
    }

    private static class StompPrincipal implements java.security.Principal {
        private final String name;

        public StompPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}