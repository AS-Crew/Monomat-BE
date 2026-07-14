package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenAuthentication;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenAuthenticationException;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenAuthenticator;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenFailureReason;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * STOMP 클라이언트 인바운드 채널 인터셉터.
 *
 * [책임]
 * - CONNECT 시 Access Token 검증
 * - 검증된 JWT userIdentifier를 STOMP 세션에 저장
 * - CONNECT 시 Redis INCR 기반 sessionSequence 발급
 * - CONNECT 시 사용자 온라인 상태 저장
 * - SUBSCRIBE / SEND 시 Redis 활성 인증 세션 재검증
 * - 로비 채팅 SUBSCRIBE 전에 enter_lobby.lua 실행
 *
 * [인증 정책]
 * CONNECT에서는 Access Token의 서명, 만료, 토큰 유형, Claim,
 * 블랙리스트 및 Redis 활성 세션을 모두 검증한다.
 *
 * 연결 이후 SEND와 SUBSCRIBE에서는 Redis 활성 세션을 다시 검증하여
 * 강제 로그인이나 로그아웃으로 폐기된 기존 연결을 차단한다.
 *
 * 연결 중 Access Token이 만료되더라도 현재 WebSocket 연결을 즉시 종료하지 않는다.
 * WebSocket 재연결 시 새로운 CONNECT 프레임의 Access Token을 다시 검증한다.
 *
 * [로비 입장 정책]
 * SessionSubscribeEvent는 구독 요청이 처리된 이후 발생한다.
 *
 * 해당 이벤트에서 Redis 입장 처리를 수행하면 Lua 실패 시에도
 * 클라이언트는 이미 구독된 상태가 될 수 있다.
 *
 * 이를 방지하기 위해 /topic/lobby/{code} 구독은 preSend 단계에서
 * enter_lobby.lua를 먼저 실행하고, 입장 상태가 확정된 경우에만 통과시킨다.
 */
@Slf4j
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    /**
     * STOMP CONNECT Authorization 헤더에서 사용하는 인증 스킴.
     */
    private static final String BEARER_PREFIX = "Bearer ";

    private static final String ENTER_FAILURE_NULL_RESULT =
            "Lua result is null";

    private static final String ENTER_FAILURE_EXCEPTION =
            "Lua execution exception";

    /**
     * ws:connection:{wsSessionId},
     * lobby:{code}:user_session:{userIdentifier},
     * lobby:{code}:user_session_seq:{userIdentifier} 매핑 TTL.
     *
     * WebSocket DISCONNECT 누락이나 서버 비정상 종료에 대비한 안전장치이다.
     */
    private static final Duration WS_CONNECTION_TTL =
            Duration.ofHours(6);

    private final StringRedisTemplate stringRedisTemplate;
    private final WebSocketMetric webSocketMetric;
    private final RedisScript<String> enterLobbyScript;
    private final AccessTokenAuthenticator accessTokenAuthenticator;

    private final LobbyEnterResultMapper lobbyEnterResultMapper =
            new LobbyEnterResultMapper();

    /**
     * 사용자 온라인 상태 TTL.
     *
     * 온라인 상태 생성 시점의 TTL 정책을 설정값으로 관리한다.
     * DISCONNECT에서는 TTL을 연장하지 않는다.
     */
    private final Duration userStatusTtl;

    /**
     * 로비 입장 Lua 재시도 횟수.
     *
     * 기본값 2는 최초 실행 1회와 재시도 1회를 의미한다.
     */
    @Value("${monomat.websocket.lobby-enter.retry-attempts:2}")
    private int lobbyEnterRetryAttempts;

    public StompChannelInterceptor(
            StringRedisTemplate stringRedisTemplate,
            WebSocketMetric webSocketMetric,
            @Qualifier("enterLobbyScript")
            RedisScript<String> enterLobbyScript,
            @Value("${monomat.websocket.user-status.ttl:PT2H}")
            Duration userStatusTtl,
            AccessTokenAuthenticator accessTokenAuthenticator
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.webSocketMetric = webSocketMetric;
        this.enterLobbyScript = enterLobbyScript;
        this.userStatusTtl = userStatusTtl;
        this.accessTokenAuthenticator = accessTokenAuthenticator;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        Map<String, Object> sessionAttributes =
                accessor.getSessionAttributes();

        StompCommand command = accessor.getCommand();

        switch (command) {
            case CONNECT ->
                    handleConnect(accessor, sessionAttributes);

            case SUBSCRIBE, SEND, UNSUBSCRIBE ->
                    validateSession(accessor, sessionAttributes);

            case DISCONNECT ->
                    handleDisconnect(sessionAttributes);

            default -> {
                // 별도 처리 불필요
            }
        }

        return message;
    }

    /**
     * STOMP CONNECT Access Token을 검증하고
     * 검증된 사용자 식별자를 WebSocket 세션에 저장한다.
     *
     * 클라이언트가 전달한 userIdentifier native header는
     * 인증 근거로 사용하지 않는다.
     */
    private void handleConnect(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes
    ) {
        String accessToken = extractAccessToken(accessor);

        AccessTokenAuthentication authentication;

        try {
            authentication =
                    accessTokenAuthenticator.authenticate(accessToken);

        } catch (AccessTokenAuthenticationException e) {
            StompErrorCode errorCode =
                    resolveAccessTokenErrorCode(e.getReason());

            log.warn(
                    "STOMP CONNECT Access Token 인증 실패 - reason: {}",
                    e.getReason()
            );

            throw new StompErrorException(errorCode, e);
        }

        String userIdentifier =
                authentication.userIdentifier();

        String wsSessionId = accessor.getSessionId();

        if (!StringUtils.hasText(wsSessionId)) {
            log.warn(
                    "STOMP CONNECT 거부: WebSocket 세션 ID 없음 - userIdentifier: {}",
                    sanitizeForLog(userIdentifier)
            );

            throw new StompErrorException(
                    StompErrorCode.CONNECT_WS_SESSION_ID_MISSING
            );
        }

        if (sessionAttributes == null) {
            log.warn(
                    "STOMP CONNECT 거부: 세션 속성 없음 - userIdentifier: {}, wsSessionId: {}",
                    sanitizeForLog(userIdentifier),
                    sanitizeForLog(wsSessionId)
            );

            throw new StompErrorException(
                    StompErrorCode.SESSION_UNAUTHENTICATED
            );
        }

        Long sessionSequence = issueSessionSequence();

        sessionAttributes.put(
                WebSocketHeaders.USER_IDENTIFIER,
                userIdentifier
        );

        sessionAttributes.put(
                WebSocketHeaders.SESSION_SEQUENCE,
                sessionSequence
        );

        /*
         * Principal 이름은 클라이언트 헤더가 아니라
         * 검증된 JWT Claim의 userIdentifier만 사용한다.
         */
        accessor.setUser(
                new StompPrincipal(userIdentifier)
        );

        log.debug(
                "STOMP CONNECT 온라인 상태 저장 시작 - userIdentifier: {}, wsSessionId: {}, sessionSequence: {}",
                sanitizeForLog(userIdentifier),
                sanitizeForLog(wsSessionId),
                sessionSequence
        );

        saveUserOnlineSession(
                userIdentifier,
                wsSessionId
        );

        webSocketMetric.increment();

        log.info(
                "STOMP CONNECT 성공 - userIdentifier: {}, wsSessionId: {}, sessionSequence: {}",
                sanitizeForLog(userIdentifier),
                sanitizeForLog(wsSessionId),
                sessionSequence
        );
    }

    /**
     * STOMP CONNECT native header에서 Access Token을 추출한다.
     *
     * Access Token 원문은 로그, 세션 속성 또는 Redis에 저장하지 않는다.
     */
    private String extractAccessToken(
            StompHeaderAccessor accessor
    ) {
        String authorization =
                accessor.getFirstNativeHeader(
                        HttpHeaders.AUTHORIZATION
                );

        if (!StringUtils.hasText(authorization)) {
            throw new StompErrorException(
                    StompErrorCode.ACCESS_TOKEN_MISSING
            );
        }

        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new StompErrorException(
                    StompErrorCode.ACCESS_TOKEN_INVALID
            );
        }

        String accessToken =
                authorization.substring(BEARER_PREFIX.length())
                        .trim();

        if (!StringUtils.hasText(accessToken)) {
            throw new StompErrorException(
                    StompErrorCode.ACCESS_TOKEN_MISSING
            );
        }

        return accessToken;
    }

    /**
     * 공통 보안 계층의 인증 실패 원인을
     * FE가 처리할 수 있는 STOMP 오류 코드로 변환한다.
     */
    private StompErrorCode resolveAccessTokenErrorCode(
            AccessTokenFailureReason reason
    ) {
        return switch (reason) {
            case EXPIRED ->
                    StompErrorCode.ACCESS_TOKEN_EXPIRED;

            case REVOKED ->
                    StompErrorCode.SESSION_REVOKED;

            case INVALID ->
                    StompErrorCode.ACCESS_TOKEN_INVALID;

            /*
             * Redis 장애 등으로 인증 상태를 신뢰할 수 없는 경우에는
             * 세션이 실제로 폐기되었다고 단정하지 않는다.
             */
            case AUTHENTICATION_UNAVAILABLE ->
                    StompErrorCode.INTERNAL_STOMP_ERROR;
        };
    }

    /**
     * 새로운 WebSocket 연결 순서값을 발급한다.
     *
     * 동일 userIdentifier의 연결이 거의 동시에 만들어질 때
     * 오래된 연결이 최신 연결을 덮어쓰지 않도록 단조 증가값을 사용한다.
     */
    private Long issueSessionSequence() {
        try {
            Long sessionSequence =
                    stringRedisTemplate.opsForValue()
                            .increment(
                                    RedisKeys.WS_SESSION_SEQUENCE
                            );

            if (sessionSequence == null) {
                throw new StompErrorException(
                        StompErrorCode.CONNECT_SESSION_SEQUENCE_FAILED
                );
            }

            return sessionSequence;

        } catch (StompErrorException e) {
            throw e;

        } catch (RuntimeException e) {
            log.error(
                    "STOMP CONNECT sessionSequence 발급 실패",
                    e
            );

            throw new StompErrorException(
                    StompErrorCode.CONNECT_SESSION_SEQUENCE_FAILED,
                    e
            );
        }
    }

    /**
     * 이미 인증된 WebSocket 연결의 Redis 활성 세션이
     * 현재도 유지되는지 검증한다. (#204, #211)
     *
     * CONNECT 단계의 JWT 검증과 별개로,
     * 연결 이후 강제 로그인이나 로그아웃이 발생한 세션을 차단한다.
     *
     * Redis 조회 실패 시 인증 상태를 신뢰하지 않는
     * fail-closed 정책을 적용한다.
     */
    private boolean isActiveAuthSession(String userIdentifier) {
        try {
            return Boolean.TRUE.equals(
                    stringRedisTemplate.hasKey(
                            RedisKeys.activeSessionKey(userIdentifier)
                    )
            );

        } catch (RuntimeException e) {
            log.warn(
                    "WebSocket 활성 인증 세션 조회 실패 - fail-closed 적용. userIdentifier: {}",
                    sanitizeForLog(userIdentifier),
                    e
            );

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
        String userIdentifier =
                sessionAttributes != null
                        ? (String) sessionAttributes.get(
                        WebSocketHeaders.USER_IDENTIFIER
                )
                        : null;

        if (!StringUtils.hasText(userIdentifier)) {
            log.warn(
                    "[{}] 인증되지 않은 세션 접근 차단",
                    accessor.getCommand()
            );

            throw new StompErrorException(
                    StompErrorCode.SESSION_UNAUTHENTICATED
            );
        }

        /*
         * 강제 로그인 또는 로그아웃으로 폐기된 세션이
         * 기존 WebSocket 연결에서 SEND/SUBSCRIBE를 계속 수행하지 못하도록 한다.
         *
         * UNSUBSCRIBE는 정리 동작이므로 활성 세션 재검증 대상에서 제외한다.
         */
        StompCommand command = accessor.getCommand();

        if ((command == StompCommand.SUBSCRIBE
                || command == StompCommand.SEND)
                && !isActiveAuthSession(userIdentifier)) {

            log.warn(
                    "[{}] revoke된 세션 접근 차단 - userIdentifier: {}",
                    command,
                    sanitizeForLog(userIdentifier)
            );

            throw new StompErrorException(
                    StompErrorCode.SESSION_REVOKED
            );
        }

        switch (command) {
            case SUBSCRIBE ->
                    handleSubscribe(
                            accessor,
                            sessionAttributes,
                            userIdentifier
                    );

            case SEND ->
                    log.info(
                            "[SEND] 메시지 발송 - 식별자: {}, 경로: {}",
                            sanitizeForLog(userIdentifier),
                            accessor.getDestination()
                    );

            case UNSUBSCRIBE ->
                    log.info(
                            "[UNSUBSCRIBE] 구독 해제 - 식별자: {}",
                            sanitizeForLog(userIdentifier)
                    );

            default -> {
                // 별도 처리 불필요
            }
        }
    }

    /**
     * SUBSCRIBE 명령을 처리한다.
     */
    private void handleSubscribe(
            StompHeaderAccessor accessor,
            Map<String, Object> sessionAttributes,
            String userIdentifier
    ) {
        String destination = accessor.getDestination();
        String wsSessionId = accessor.getSessionId();

        if (StompDestinations.isLobbyChatSubscription(destination)) {
            String lobbyCode =
                    StompDestinations.extractLobbyCode(destination);

            if (sessionAttributes != null) {
                sessionAttributes.put(
                        WebSocketHeaders.ROOM_ID,
                        lobbyCode
                );
            }

            String enterResult =
                    executeEnterLobbyBeforeSubscribe(
                            lobbyCode,
                            userIdentifier,
                            wsSessionId,
                            sessionAttributes
                    );

            if (sessionAttributes != null) {
                sessionAttributes.put(
                        WebSocketHeaders.LOBBY_ENTER_RESULT,
                        enterResult
                );
            }

            log.info(
                    "[SUBSCRIBE] 로비 입장 확정 후 구독 허용 - 식별자: {}, 로비: {}, wsSessionId: {}, result: {}",
                    sanitizeForLog(userIdentifier),
                    lobbyCode,
                    sanitizeForLog(wsSessionId),
                    enterResult
            );

            return;
        }

        if (StompDestinations.isLobbySubscription(destination)) {
            String roomId =
                    StompDestinations.extractLobbyCode(destination);

            if (sessionAttributes != null) {
                sessionAttributes.put(
                        WebSocketHeaders.ROOM_ID,
                        roomId
                );
            }
        }

        log.info(
                "[SUBSCRIBE] 구독 - 식별자: {}, 경로: {}",
                sanitizeForLog(userIdentifier),
                destination
        );
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
        if (!StringUtils.hasText(wsSessionId)) {
            throw new StompErrorException(
                    StompErrorCode.LOBBY_ENTER_WS_SESSION_MISSING
            );
        }

        long sessionSequence =
                extractSessionSequence(sessionAttributes);

        RuntimeException lastException = null;
        int maxAttempts =
                Math.max(1, lobbyEnterRetryAttempts);

        for (int attempt = 1;
             attempt <= maxAttempts;
             attempt++) {

            try {
                String result =
                        executeEnterLobbyScript(
                                lobbyCode,
                                userIdentifier,
                                wsSessionId,
                                sessionSequence
                        );

                LobbyEnterResultMapper.LobbyEnterResultType resultType =
                        lobbyEnterResultMapper.parse(result);

                switch (resultType) {
                    case ENTERED,
                         ALREADY_JOINED,
                         SESSION_REPLACED -> {
                        return result;
                    }

                    case UNKNOWN -> {
                        if (result == null) {
                            log.warn(
                                    "로비 입장 Lua 결과 null - 재시도 여부 확인. attempt: {}/{}, 로비: {}, 식별자: {}, wsSessionId: {}",
                                    attempt,
                                    maxAttempts,
                                    lobbyCode,
                                    sanitizeForLog(userIdentifier),
                                    sanitizeForLog(wsSessionId)
                            );

                            continue;
                        }

                        log.error(
                                "로비 입장 Lua 알 수 없는 반환값 - result: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                                result,
                                lobbyCode,
                                sanitizeForLog(userIdentifier),
                                sanitizeForLog(wsSessionId)
                        );

                        throwLobbyEnterFailure(
                                wsSessionId,
                                resultType.resolveErrorCode()
                        );
                    }

                    default ->
                            throwLobbyEnterFailure(
                                    wsSessionId,
                                    resultType.resolveErrorCode()
                            );
                }

            } catch (StompErrorException e) {
                throw e;

            } catch (RuntimeException e) {
                lastException = e;

                log.warn(
                        "로비 입장 Lua 실행 예외 - 재시도 여부 확인. attempt: {}/{}, 로비: {}, 식별자: {}, wsSessionId: {}, message: {}",
                        attempt,
                        maxAttempts,
                        lobbyCode,
                        sanitizeForLog(userIdentifier),
                        sanitizeForLog(wsSessionId),
                        e.getMessage()
                );
            }
        }

        if (lastException != null) {
            log.error(
                    "로비 입장 Lua 최종 실패 - reason: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                    ENTER_FAILURE_EXCEPTION,
                    lobbyCode,
                    sanitizeForLog(userIdentifier),
                    sanitizeForLog(wsSessionId),
                    lastException
            );

        } else {
            log.error(
                    "로비 입장 Lua 최종 실패 - reason: {}, 로비: {}, 식별자: {}, wsSessionId: {}",
                    ENTER_FAILURE_NULL_RESULT,
                    lobbyCode,
                    sanitizeForLog(userIdentifier),
                    sanitizeForLog(wsSessionId)
            );
        }

        cleanupWsConnection(wsSessionId);

        throw new StompErrorException(
                StompErrorCode.LOBBY_ENTER_TEMPORARILY_UNAVAILABLE
        );
    }

    /**
     * STOMP 세션 속성에서 sessionSequence를 추출한다.
     */
    private long extractSessionSequence(
            Map<String, Object> sessionAttributes
    ) {
        if (sessionAttributes == null) {
            throw new StompErrorException(
                    StompErrorCode.LOBBY_ENTER_SESSION_ATTRIBUTES_MISSING
            );
        }

        Object value =
                sessionAttributes.get(
                        WebSocketHeaders.SESSION_SEQUENCE
                );

        if (value instanceof Number number) {
            return number.longValue();
        }

        throw new StompErrorException(
                StompErrorCode.LOBBY_ENTER_SEQUENCE_MISSING
        );
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
                RedisKeys.lobbyUserSessionKey(
                        lobbyCode,
                        userIdentifier
                ),
                RedisKeys.lobbyUserSessionSequenceKey(
                        lobbyCode,
                        userIdentifier
                ),
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        return stringRedisTemplate.execute(
                enterLobbyScript,
                keys,
                userIdentifier,
                lobbyCode,
                String.valueOf(
                        WS_CONNECTION_TTL.toMillis()
                ),
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
     * 먼저 보상 삭제한 뒤 오류를 발생시킨다.
     */
    private void throwLobbyEnterFailure(
            String wsSessionId,
            StompErrorCode errorCode
    ) {
        cleanupWsConnection(wsSessionId);
        throw new StompErrorException(errorCode);
    }

    /**
     * 로비 입장 실패 시 현재 ws:connection 키를 보상 삭제한다.
     */
    private void cleanupWsConnection(String wsSessionId) {
        if (!StringUtils.hasText(wsSessionId)) {
            return;
        }

        try {
            stringRedisTemplate.delete(
                    RedisKeys.wsConnectionKey(wsSessionId)
            );

        } catch (RuntimeException e) {
            log.error(
                    "로비 입장 실패 후 ws:connection 보상 삭제 실패 - wsSessionId: {}",
                    sanitizeForLog(wsSessionId),
                    e
            );
        }
    }

    private void handleDisconnect(
            Map<String, Object> sessionAttributes
    ) {
        String userIdentifier =
                sessionAttributes != null
                        ? (String) sessionAttributes.get(
                        WebSocketHeaders.USER_IDENTIFIER
                )
                        : null;

        if (!StringUtils.hasText(userIdentifier)) {
            userIdentifier =
                    WebSocketHeaders.UNKNOWN_IDENTIFIER;
        }

        log.info(
                "STOMP DISCONNECT: {}",
                sanitizeForLog(userIdentifier)
        );
    }

    /**
     * 로그 인젝션과 과도한 로그 길이를 방지한다.
     */
    private String sanitizeForLog(String value) {
        if (value == null) {
            return "null";
        }

        String sanitized =
                value.replaceAll("[\\r\\n\\t]", "");

        return sanitized.length() > 50
                ? sanitized.substring(0, 50) + "..."
                : sanitized;
    }

    /**
     * 사용자 온라인 상태와 현재 WebSocket 세션을 Redis에 저장한다.
     *
     * [저장 구조]
     * - user_status:{userIdentifier}:sessions = Set<wsSessionId>
     * - user_status:{userIdentifier} = ONLINE
     *
     * 온라인 상태 TTL은 CONNECT 시점의 생존 신호를 기준으로 설정한다.
     * DISCONNECT는 세션 정리 이벤트이므로 TTL 연장 기준으로 사용하지 않는다.
     */
    private void saveUserOnlineSession(
            String userIdentifier,
            String wsSessionId
    ) {
        String userStatusKey =
                RedisKeys.userStatusKey(userIdentifier);

        String userStatusSessionsKey =
                RedisKeys.userStatusSessionsKey(userIdentifier);

        try {
            stringRedisTemplate.opsForSet()
                    .add(
                            userStatusSessionsKey,
                            wsSessionId
                    );

            stringRedisTemplate.expire(
                    userStatusSessionsKey,
                    userStatusTtl
            );

            stringRedisTemplate.opsForValue()
                    .set(
                            userStatusKey,
                            WebSocketHeaders.STATUS_ONLINE,
                            userStatusTtl
                    );

        } catch (RuntimeException e) {
            log.error(
                    "STOMP CONNECT 온라인 상태 저장 실패 - userIdentifier: {}, wsSessionId: {}, userStatusKey: {}, userStatusSessionsKey: {}",
                    sanitizeForLog(userIdentifier),
                    sanitizeForLog(wsSessionId),
                    userStatusKey,
                    userStatusSessionsKey,
                    e
            );

            throw new StompErrorException(
                    StompErrorCode.CONNECT_ONLINE_STATUS_FAILED,
                    e
            );
        }
    }

    /**
     * Spring 사용자 목적지 라우팅에서 사용하는 STOMP Principal.
     */
    private static class StompPrincipal
            implements java.security.Principal {

        private final String name;

        private StompPrincipal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}