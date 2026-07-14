package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.security.jwt.JwtClaims;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompChannelInterceptorTest {

    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER_IDENTIFIER = "22222222-2222-2222-2222-222222222222";
    private static final String VALID_TOKEN = "valid-access-token";
    private static final String BEARER_VALID = "Bearer " + VALID_TOKEN;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private WebSocketMetric webSocketMetric;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @SuppressWarnings("unchecked")
    private final RedisScript<String> enterLobbyScript = mock(RedisScript.class);

    private final MessageChannel channel = mock(MessageChannel.class);

    private StompChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompChannelInterceptor(
                stringRedisTemplate,
                webSocketMetric,
                enterLobbyScript,
                jwtTokenProvider,
                Duration.ofHours(2)
        );
    }

    // =========================================================
    // CONNECT - Access Token 인증 (#211)
    // =========================================================

    @Test
    @DisplayName("Authorization 헤더가 없으면 CONNECT는 ACCESS_TOKEN_MISSING으로 거부된다")
    void connectWithoutAuthorizationHeaderIsRejected() {
        // given
        Message<byte[]> connect = buildConnectMessage(null, null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.ACCESS_TOKEN_MISSING);
    }

    @Test
    @DisplayName("Bearer 형식이 아닌 Authorization 헤더는 ACCESS_TOKEN_MISSING으로 거부된다")
    void connectWithNonBearerAuthorizationIsRejected() {
        // given
        Message<byte[]> connect = buildConnectMessage("Basic abcdef", null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.ACCESS_TOKEN_MISSING);
    }

    @Test
    @DisplayName("서명이 유효하지 않은 Access Token은 ACCESS_TOKEN_INVALID로 거부된다")
    void connectWithInvalidSignatureIsRejected() {
        // given
        when(jwtTokenProvider.parseClaims(VALID_TOKEN)).thenThrow(new JwtException("bad signature"));
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("만료된 Access Token은 ACCESS_TOKEN_EXPIRED로 거부된다")
    void connectWithExpiredTokenIsRejected() {
        // given
        when(jwtTokenProvider.parseClaims(VALID_TOKEN)).thenThrow(mock(ExpiredJwtException.class));
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.ACCESS_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("userIdentifier claim이 없는 토큰(예: Refresh Token)은 ACCESS_TOKEN_INVALID로 거부된다")
    void connectWithoutUserIdentifierClaimIsRejected() {
        // given
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get(JwtClaims.USER_IDENTIFIER, String.class)).thenReturn(null);
        when(jwtTokenProvider.parseClaims(VALID_TOKEN)).thenReturn(claims);
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("블랙리스트 처리된 Access Token은 ACCESS_TOKEN_INVALID로 거부된다")
    void connectWithBlacklistedTokenIsRejected() {
        // given
        givenValidClaims(USER_IDENTIFIER);
        givenBlacklisted(true);
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.ACCESS_TOKEN_INVALID);
    }

    @Test
    @DisplayName("유효한 토큰이지만 활성 세션이 없으면 CONNECT_SESSION_REVOKED로 거부된다")
    void connectWithoutActiveSessionIsRejected() {
        // given
        givenValidClaims(USER_IDENTIFIER);
        givenBlacklisted(false);
        givenActiveSession(false);
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, null);

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(connect, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.CONNECT_SESSION_REVOKED);
    }

    @Test
    @DisplayName("유효한 토큰과 활성 세션이면 CONNECT가 성공하고 세션 속성에 JWT userIdentifier가 저장된다")
    void connectWithValidTokenSucceeds() {
        // given
        givenValidClaims(USER_IDENTIFIER);
        givenBlacklisted(false);
        givenActiveSession(true);
        givenConnectRedisWrites();
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, null);

        // when
        interceptor.preSend(connect, channel);

        // then
        Map<String, Object> attributes = sessionAttributesOf(connect);
        assertThat(attributes.get(WebSocketHeaders.USER_IDENTIFIER)).isEqualTo(USER_IDENTIFIER);
        assertThat(attributes).containsKey(WebSocketHeaders.SESSION_SEQUENCE);
    }

    @Test
    @DisplayName("다른 userIdentifier를 native header로 동봉해도 JWT claim의 식별자만 신뢰한다")
    void connectIgnoresClientSuppliedUserIdentifier() {
        // given - JWT는 USER_IDENTIFIER, native header에는 공격자가 다른 식별자를 동봉
        givenValidClaims(USER_IDENTIFIER);
        givenBlacklisted(false);
        givenActiveSession(true);
        givenConnectRedisWrites();
        Message<byte[]> connect = buildConnectMessage(BEARER_VALID, OTHER_USER_IDENTIFIER);

        // when
        interceptor.preSend(connect, channel);

        // then - 세션에는 JWT 식별자만 저장되고, 동봉된 식별자는 무시된다
        Map<String, Object> attributes = sessionAttributesOf(connect);
        assertThat(attributes.get(WebSocketHeaders.USER_IDENTIFIER)).isEqualTo(USER_IDENTIFIER);
    }

    // =========================================================
    // CONNECT 이후 - revoke 재검증 (기존 회귀)
    // =========================================================

    @Test
    @DisplayName("revoke된 세션의 SEND는 SESSION_REVOKED로 차단된다")
    void revokedSessionSendIsBlocked() {
        // given
        givenActiveSession(false);
        Message<byte[]> send = buildMessage(StompCommand.SEND, "/app/game/chat");

        // when
        Throwable thrown = catchThrowable(() -> interceptor.preSend(send, channel));

        // then
        assertThat(thrown)
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.SESSION_REVOKED);
    }

    @Test
    @DisplayName("revoke된 세션의 SUBSCRIBE는 SESSION_REVOKED로 차단된다")
    void revokedSessionSubscribeIsBlocked() {
        // given
        givenActiveSession(false);
        Message<byte[]> subscribe = buildMessage(StompCommand.SUBSCRIBE, "/topic/lobby-list");

        // when / then
        assertThatThrownBy(() -> interceptor.preSend(subscribe, channel))
                .isInstanceOf(StompErrorException.class)
                .extracting(e -> ((StompErrorException) e).getErrorCode())
                .isEqualTo(StompErrorCode.SESSION_REVOKED);
    }

    @Test
    @DisplayName("active session 마커가 살아있으면 SEND는 통과한다")
    void activeSessionSendPasses() {
        // given
        givenActiveSession(true);
        Message<byte[]> send = buildMessage(StompCommand.SEND, "/app/game/chat");

        // when / then - 예외 없이 통과
        assertThat(interceptor.preSend(send, channel)).isSameAs(send);
    }

    @Test
    @DisplayName("UNSUBSCRIBE는 정리 동작이므로 revoke 상태에서도 재검증 없이 통과한다")
    void unsubscribeIsNotRevalidated() {
        // given - active session 조회가 일어나지 않아야 하므로 stub하지 않는다
        Message<byte[]> unsubscribe = buildMessage(StompCommand.UNSUBSCRIBE, null);

        // when / then
        assertThat(interceptor.preSend(unsubscribe, channel)).isSameAs(unsubscribe);
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    private void givenValidClaims(String userIdentifier) {
        Claims claims = mock(Claims.class);
        lenient().when(claims.getSubject()).thenReturn("1");
        lenient().when(claims.get(JwtClaims.USER_IDENTIFIER, String.class)).thenReturn(userIdentifier);
        lenient().when(claims.get(JwtClaims.USER_TYPE, String.class)).thenReturn("GUEST");
        when(jwtTokenProvider.parseClaims(VALID_TOKEN)).thenReturn(claims);
    }

    private void givenBlacklisted(boolean blacklisted) {
        String key = RedisKeys.accessTokenBlacklistKey(TokenHashUtils.sha256(VALID_TOKEN));
        lenient().when(stringRedisTemplate.hasKey(key)).thenReturn(blacklisted);
    }

    private void givenActiveSession(boolean active) {
        lenient().when(stringRedisTemplate.hasKey(RedisKeys.activeSessionKey(USER_IDENTIFIER)))
                .thenReturn(active);
    }

    private void givenConnectRedisWrites() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(valueOps.increment(RedisKeys.WS_SESSION_SEQUENCE)).thenReturn(1L);
        lenient().when(setOps.add(anyString(), anyString())).thenReturn(1L);
    }

    private Message<byte[]> buildConnectMessage(String authorization, String userIdentifierHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("ws-session-1");
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        if (userIdentifierHeader != null) {
            accessor.setNativeHeader(WebSocketHeaders.USER_IDENTIFIER, userIdentifierHeader);
        }
        accessor.setSessionAttributes(new HashMap<>());
        // setUser()가 CONNECT 성공 시 헤더를 수정하므로 mutable 상태를 유지한다.
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessionAttributesOf(Message<byte[]> message) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        return accessor.getSessionAttributes();
    }

    private Message<byte[]> buildMessage(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("ws-session-1");
        if (destination != null) {
            accessor.setDestination(destination);
        }

        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(WebSocketHeaders.USER_IDENTIFIER, USER_IDENTIFIER);
        accessor.setSessionAttributes(sessionAttributes);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
