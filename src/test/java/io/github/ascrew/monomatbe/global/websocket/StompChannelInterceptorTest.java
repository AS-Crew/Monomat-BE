package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompChannelInterceptorTest {

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final WebSocketMetric webSocketMetric = mock(WebSocketMetric.class);
    private final RedisScript<String> enterLobbyScript = mock(RedisScript.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MessageChannel messageChannel = mock(MessageChannel.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final StompChannelInterceptor interceptor = new StompChannelInterceptor(
            stringRedisTemplate,
            webSocketMetric,
            enterLobbyScript,
            Duration.ofHours(2),
            eventPublisher
    );

    @Test
    @DisplayName("DISCONNECT된 세션이 현재 유효 로비 세션이면 PlayerLeaveEvent를 발행한다")
    void preSend_disconnectCurrentLobbySession_publishesPlayerLeaveEvent() {
        // given
        String lobbyCode = "ABC123";
        String userIdentifier = "user-a";
        String wsSessionId = "ws-session-current";

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier)))
                .thenReturn(wsSessionId);

        Message<byte[]> message = disconnectMessage(
                wsSessionId,
                userIdentifier,
                lobbyCode
        );

        // when
        interceptor.preSend(message, messageChannel);

        // then
        ArgumentCaptor<PlayerLeaveEvent> eventCaptor =
                ArgumentCaptor.forClass(PlayerLeaveEvent.class);

        verify(eventPublisher).publishEvent(eventCaptor.capture());

        PlayerLeaveEvent event = eventCaptor.getValue();
        assertThat(event.lobbyCode()).isEqualTo(lobbyCode);
        assertThat(event.userIdentifier()).isEqualTo(userIdentifier);
    }

    @Test
    @DisplayName("DISCONNECT된 세션이 최신 로비 세션이 아니면 PlayerLeaveEvent를 발행하지 않는다")
    void preSend_disconnectStaleLobbySession_doesNotPublishPlayerLeaveEvent() {
        // given
        String lobbyCode = "ABC123";
        String userIdentifier = "user-a";
        String disconnectedWsSessionId = "ws-session-old";
        String currentWsSessionId = "ws-session-current";

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.lobbyUserSessionKey(lobbyCode, userIdentifier)))
                .thenReturn(currentWsSessionId);

        Message<byte[]> message = disconnectMessage(
                disconnectedWsSessionId,
                userIdentifier,
                lobbyCode
        );

        // when
        interceptor.preSend(message, messageChannel);

        // then
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(PlayerLeaveEvent.class));
    }

    @Test
    @DisplayName("DISCONNECT 세션에 로비 코드가 없으면 PlayerLeaveEvent를 발행하지 않는다")
    void preSend_disconnectWithoutLobbyCode_doesNotPublishPlayerLeaveEvent() {
        // given
        String userIdentifier = "user-a";
        String wsSessionId = "ws-session-current";

        Message<byte[]> message = disconnectMessage(
                wsSessionId,
                userIdentifier,
                null
        );

        // when
        interceptor.preSend(message, messageChannel);

        // then
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(PlayerLeaveEvent.class));
    }

    @Test
    @DisplayName("DISCONNECT 세션에 사용자 식별자가 없으면 PlayerLeaveEvent를 발행하지 않는다")
    void preSend_disconnectWithoutUserIdentifier_doesNotPublishPlayerLeaveEvent() {
        // given
        String lobbyCode = "ABC123";
        String wsSessionId = "ws-session-current";

        Message<byte[]> message = disconnectMessage(
                wsSessionId,
                null,
                lobbyCode
        );

        // when
        interceptor.preSend(message, messageChannel);

        // then
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any(PlayerLeaveEvent.class));
    }

    private Message<byte[]> disconnectMessage(
            String wsSessionId,
            String userIdentifier,
            String lobbyCode
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(wsSessionId);

        Map<String, Object> sessionAttributes = new HashMap<>();

        if (userIdentifier != null) {
            sessionAttributes.put(WebSocketHeaders.USER_IDENTIFIER, userIdentifier);
        }

        if (lobbyCode != null) {
            sessionAttributes.put(WebSocketHeaders.ROOM_ID, lobbyCode);
        }

        accessor.setSessionAttributes(sessionAttributes);

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}