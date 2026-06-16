package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompChannelInterceptorTest {

    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private WebSocketMetric webSocketMetric;

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
                Duration.ofHours(2)
        );
    }

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

    private void givenActiveSession(boolean active) {
        lenient().when(stringRedisTemplate.hasKey(RedisKeys.activeSessionKey(USER_IDENTIFIER)))
                .thenReturn(active);
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
