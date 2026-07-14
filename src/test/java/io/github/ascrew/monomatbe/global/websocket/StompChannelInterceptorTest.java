package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenAuthentication;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenAuthenticationException;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenAuthenticator;
import io.github.ascrew.monomatbe.global.security.jwt.AccessTokenFailureReason;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompChannelInterceptorTest {

    private static final Long USER_ID = 1L;

    private static final String USER_IDENTIFIER =
            "11111111-1111-1111-1111-111111111111";

    private static final String FORGED_USER_IDENTIFIER =
            "22222222-2222-2222-2222-222222222222";

    private static final String ACCESS_TOKEN =
            "valid-access-token";

    private static final String AUTHORIZATION =
            "Bearer " + ACCESS_TOKEN;

    private static final String WS_SESSION_ID =
            "ws-session-1";

    private static final Duration USER_STATUS_TTL =
            Duration.ofHours(2);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private WebSocketMetric webSocketMetric;

    @Mock
    private AccessTokenAuthenticator accessTokenAuthenticator;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @SuppressWarnings("unchecked")
    private final RedisScript<String> enterLobbyScript =
            mock(RedisScript.class);

    private final MessageChannel channel =
            mock(MessageChannel.class);

    private StompChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompChannelInterceptor(
                stringRedisTemplate,
                webSocketMetric,
                enterLobbyScript,
                USER_STATUS_TTL,
                accessTokenAuthenticator
        );
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 ACCESS_TOKEN_MISSING으로 차단된다")
    void missingAuthorizationIsBlocked() {
        Message<byte[]> connect =
                buildConnectMessage(
                        null,
                        USER_IDENTIFIER
                );

        assertStompError(
                connect,
                StompErrorCode.ACCESS_TOKEN_MISSING
        );

        verifyNoInteractions(accessTokenAuthenticator);
    }

    @Test
    @DisplayName("Bearer 형식이 아니면 ACCESS_TOKEN_INVALID로 차단된다")
    void invalidBearerFormatIsBlocked() {
        Message<byte[]> connect =
                buildConnectMessage(
                        ACCESS_TOKEN,
                        USER_IDENTIFIER
                );

        assertStompError(
                connect,
                StompErrorCode.ACCESS_TOKEN_INVALID
        );

        verifyNoInteractions(accessTokenAuthenticator);
    }

    @Test
    @DisplayName("Bearer Token 값이 비어 있으면 ACCESS_TOKEN_MISSING으로 차단된다")
    void blankBearerTokenIsBlocked() {
        Message<byte[]> connect =
                buildConnectMessage(
                        "Bearer ",
                        USER_IDENTIFIER
                );

        assertStompError(
                connect,
                StompErrorCode.ACCESS_TOKEN_MISSING
        );

        verifyNoInteractions(accessTokenAuthenticator);
    }

    @Test
    @DisplayName("만료된 Access Token은 ACCESS_TOKEN_EXPIRED로 변환된다")
    void expiredAccessTokenIsBlocked() {
        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenThrow(
                        new AccessTokenAuthenticationException(
                                AccessTokenFailureReason.EXPIRED
                        )
                );

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        null
                );

        assertStompError(
                connect,
                StompErrorCode.ACCESS_TOKEN_EXPIRED
        );
    }

    @Test
    @DisplayName("유효하지 않은 Access Token은 ACCESS_TOKEN_INVALID로 변환된다")
    void invalidAccessTokenIsBlocked() {
        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenThrow(
                        new AccessTokenAuthenticationException(
                                AccessTokenFailureReason.INVALID
                        )
                );

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        null
                );

        assertStompError(
                connect,
                StompErrorCode.ACCESS_TOKEN_INVALID
        );
    }

    @Test
    @DisplayName("폐기된 인증 세션은 SESSION_REVOKED로 변환된다")
    void revokedAccessTokenSessionIsBlocked() {
        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenThrow(
                        new AccessTokenAuthenticationException(
                                AccessTokenFailureReason.REVOKED
                        )
                );

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        null
                );

        assertStompError(
                connect,
                StompErrorCode.SESSION_REVOKED
        );
    }

    @Test
    @DisplayName("인증 저장소 장애는 INTERNAL_STOMP_ERROR로 변환된다")
    void authenticationUnavailableIsBlocked() {
        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenThrow(
                        new AccessTokenAuthenticationException(
                                AccessTokenFailureReason.AUTHENTICATION_UNAVAILABLE
                        )
                );

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        null
                );

        assertStompError(
                connect,
                StompErrorCode.INTERNAL_STOMP_ERROR
        );
    }

    @Test
    @DisplayName("유효한 Access Token이면 CONNECT 인증 정보가 세션에 저장된다")
    void validAccessTokenConnects() {
        givenSuccessfulConnect();

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        null
                );

        Message<?> result =
                interceptor.preSend(connect, channel);

        StompHeaderAccessor resultAccessor =
                MessageHeaderAccessor.getAccessor(
                        result,
                        StompHeaderAccessor.class
                );

        assertThat(resultAccessor).isNotNull();
        assertThat(resultAccessor.getSessionAttributes())
                .containsEntry(
                        WebSocketHeaders.USER_IDENTIFIER,
                        USER_IDENTIFIER
                )
                .containsEntry(
                        WebSocketHeaders.SESSION_SEQUENCE,
                        1L
                );

        assertThat(resultAccessor.getUser()).isNotNull();
        assertThat(resultAccessor.getUser().getName())
                .isEqualTo(USER_IDENTIFIER);

        verify(accessTokenAuthenticator)
                .authenticate(ACCESS_TOKEN);

        verify(webSocketMetric)
                .increment();
    }

    @Test
    @DisplayName("클라이언트 userIdentifier와 관계없이 JWT의 식별자를 사용한다")
    void jwtUserIdentifierHasPriority() {
        givenSuccessfulConnect();

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        FORGED_USER_IDENTIFIER
                );

        Message<?> result =
                interceptor.preSend(connect, channel);

        StompHeaderAccessor resultAccessor =
                MessageHeaderAccessor.getAccessor(
                        result,
                        StompHeaderAccessor.class
                );

        assertThat(resultAccessor).isNotNull();

        Map<String, Object> sessionAttributes =
                resultAccessor.getSessionAttributes();

        assertThat(sessionAttributes)
                .containsEntry(
                        WebSocketHeaders.USER_IDENTIFIER,
                        USER_IDENTIFIER
                );

        assertThat(sessionAttributes)
                .doesNotContainValue(FORGED_USER_IDENTIFIER);
    }

    @Test
    @DisplayName("Access Token 원문은 STOMP 세션 속성에 저장되지 않는다")
    void accessTokenIsNotStoredInSessionAttributes() {
        givenSuccessfulConnect();

        Message<byte[]> connect =
                buildConnectMessage(
                        AUTHORIZATION,
                        null
                );

        Message<?> result =
                interceptor.preSend(connect, channel);

        StompHeaderAccessor resultAccessor =
                MessageHeaderAccessor.getAccessor(
                        result,
                        StompHeaderAccessor.class
                );

        assertThat(resultAccessor).isNotNull();

        Map<String, Object> sessionAttributes =
                resultAccessor.getSessionAttributes();

        assertThat(sessionAttributes)
                .doesNotContainValue(ACCESS_TOKEN)
                .doesNotContainValue(AUTHORIZATION);
    }

    @Test
    @DisplayName("revoke된 세션의 SEND는 SESSION_REVOKED로 차단된다")
    void revokedSessionSendIsBlocked() {
        givenActiveSession(false);

        Message<byte[]> send =
                buildSessionMessage(
                        StompCommand.SEND,
                        "/app/game/chat"
                );

        assertStompError(
                send,
                StompErrorCode.SESSION_REVOKED
        );
    }

    @Test
    @DisplayName("revoke된 세션의 SUBSCRIBE는 SESSION_REVOKED로 차단된다")
    void revokedSessionSubscribeIsBlocked() {
        givenActiveSession(false);

        Message<byte[]> subscribe =
                buildSessionMessage(
                        StompCommand.SUBSCRIBE,
                        "/topic/lobby-list"
                );

        assertStompError(
                subscribe,
                StompErrorCode.SESSION_REVOKED
        );
    }

    @Test
    @DisplayName("active session 마커가 살아있으면 SEND는 통과한다")
    void activeSessionSendPasses() {
        givenActiveSession(true);

        Message<byte[]> send =
                buildSessionMessage(
                        StompCommand.SEND,
                        "/app/game/chat"
                );

        assertThat(
                interceptor.preSend(send, channel)
        ).isSameAs(send);
    }

    @Test
    @DisplayName("UNSUBSCRIBE는 정리 동작이므로 revoke 상태에서도 재검증 없이 통과한다")
    void unsubscribeIsNotRevalidated() {
        Message<byte[]> unsubscribe =
                buildSessionMessage(
                        StompCommand.UNSUBSCRIBE,
                        null
                );

        assertThat(
                interceptor.preSend(unsubscribe, channel)
        ).isSameAs(unsubscribe);
    }

    private void givenSuccessfulConnect() {
        when(accessTokenAuthenticator.authenticate(ACCESS_TOKEN))
                .thenReturn(validAuthentication());

        when(stringRedisTemplate.opsForValue())
                .thenReturn(valueOperations);

        when(valueOperations.increment(
                RedisKeys.WS_SESSION_SEQUENCE
        )).thenReturn(1L);

        when(stringRedisTemplate.opsForSet())
                .thenReturn(setOperations);
    }

    private void givenActiveSession(boolean active) {
        when(stringRedisTemplate.hasKey(
                RedisKeys.activeSessionKey(USER_IDENTIFIER)
        )).thenReturn(active);
    }

    private AccessTokenAuthentication validAuthentication() {
        return new AccessTokenAuthentication(
                USER_ID,
                USER_IDENTIFIER,
                UserType.REGISTERED,
                UserRole.USER
        );
    }

    private Message<byte[]> buildConnectMessage(
            String authorization,
            String clientUserIdentifier
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(
                        StompCommand.CONNECT
                );

        accessor.setSessionId(WS_SESSION_ID);
        accessor.setSessionAttributes(new HashMap<>());

        if (authorization != null) {
            accessor.setNativeHeader(
                    HttpHeaders.AUTHORIZATION,
                    authorization
            );
        }

        /*
         * 공격자가 임의 식별자를 전달하는 상황을 재현한다.
         * 실제 인증에서는 이 값을 사용하면 안 된다.
         */
        if (clientUserIdentifier != null) {
            accessor.setNativeHeader(
                    WebSocketHeaders.USER_IDENTIFIER,
                    clientUserIdentifier
            );
        }

        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }

    private Message<byte[]> buildSessionMessage(
            StompCommand command,
            String destination
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(command);

        accessor.setSessionId(WS_SESSION_ID);

        if (destination != null) {
            accessor.setDestination(destination);
        }

        Map<String, Object> sessionAttributes =
                new HashMap<>();

        sessionAttributes.put(
                WebSocketHeaders.USER_IDENTIFIER,
                USER_IDENTIFIER
        );

        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );
    }

    private void assertStompError(
            Message<byte[]> message,
            StompErrorCode expectedErrorCode
    ) {
        assertThatThrownBy(
                () -> interceptor.preSend(message, channel)
        )
                .isInstanceOf(StompErrorException.class)
                .extracting(
                        exception ->
                                ((StompErrorException) exception)
                                        .getErrorCode()
                )
                .isEqualTo(expectedErrorCode);
    }
}