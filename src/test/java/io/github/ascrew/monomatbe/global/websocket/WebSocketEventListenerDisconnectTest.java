package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebSocketEventListenerDisconnectTest {

    private static final String LOBBY_CODE = "TEST94";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String WS_SESSION_ID = "ws-session-94";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisPublisher redisPublisher = mock(RedisPublisher.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final WebSocketMetric webSocketMetric = mock(WebSocketMetric.class);
    private final JsonMapper pubSubJsonMapper = JsonMapper.builder().build();

    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final WebSocketEventListener listener = new WebSocketEventListener(
            redisTemplate,
            redisPublisher,
            messagingTemplate,
            eventPublisher,
            webSocketMetric,
            pubSubJsonMapper,
            Duration.ofHours(2)
    );

    @Test
    @DisplayName("DISCONNECT 시 wsSessionId로 로비를 역추적해 PlayerLeaveEvent를 발행한다")
    void publishPlayerLeaveEventWhenValidLobbySessionDisconnects() {
        // given
        doReturn(hashOperations).when(redisTemplate).opsForHash();
        doReturn(setOperations).when(redisTemplate).opsForSet();
        doReturn(valueOperations).when(redisTemplate).opsForValue();

        doReturn(Map.of(WebSocketHeaders.SESSION_LOBBY_CODE, LOBBY_CODE))
                .when(hashOperations)
                .entries(RedisKeys.wsConnectionKey(WS_SESSION_ID));

        doReturn(WS_SESSION_ID)
                .when(valueOperations)
                .get(RedisKeys.lobbyUserSessionKey(LOBBY_CODE, USER_IDENTIFIER));

        doReturn(0L)
                .when(setOperations)
                .size(RedisKeys.userStatusSessionsKey(USER_IDENTIFIER));

        SessionDisconnectEvent event = disconnectEvent();

        // when
        listener.handleDisconnectEvent(event);

        // then
        verify(eventPublisher).publishEvent(
                new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER)
        );

        verify(redisPublisher).publish(
                eq(StompDestinations.subscribeLobbyChat(LOBBY_CODE)),
                any()
        );

        verify(setOperations).remove(
                RedisKeys.userStatusSessionsKey(USER_IDENTIFIER),
                WS_SESSION_ID
        );

        verify(redisTemplate).delete(RedisKeys.wsConnectionKey(WS_SESSION_ID));
        verify(redisTemplate).delete(RedisKeys.lobbyUserSessionKey(LOBBY_CODE, USER_IDENTIFIER));
        verify(redisTemplate).delete(RedisKeys.lobbyUserSessionSequenceKey(LOBBY_CODE, USER_IDENTIFIER));
        verify(redisTemplate).delete(RedisKeys.userStatusKey(USER_IDENTIFIER));
        verify(redisTemplate).delete(RedisKeys.userStatusSessionsKey(USER_IDENTIFIER));
        verify(webSocketMetric).decrement();
    }

    @Test
    @DisplayName("stale WebSocket 세션 DISCONNECT는 실제 퇴장 이벤트를 발행하지 않는다")
    void doesNotPublishLeaveEventWhenStaleLobbySessionDisconnects() {
        // given
        String latestWsSessionId = "ws-session-latest";

        doReturn(hashOperations).when(redisTemplate).opsForHash();
        doReturn(setOperations).when(redisTemplate).opsForSet();
        doReturn(valueOperations).when(redisTemplate).opsForValue();

        doReturn(Map.of(WebSocketHeaders.SESSION_LOBBY_CODE, LOBBY_CODE))
                .when(hashOperations)
                .entries(RedisKeys.wsConnectionKey(WS_SESSION_ID));

        doReturn(latestWsSessionId)
                .when(valueOperations)
                .get(RedisKeys.lobbyUserSessionKey(LOBBY_CODE, USER_IDENTIFIER));

        SessionDisconnectEvent event = disconnectEvent();

        // when
        listener.handleDisconnectEvent(event);

        // then
        verify(eventPublisher, never()).publishEvent(any(PlayerLeaveEvent.class));
        verify(redisPublisher, never()).publish(anyString(), any());

        verify(setOperations).remove(
                RedisKeys.userStatusSessionsKey(USER_IDENTIFIER),
                WS_SESSION_ID
        );

        verify(redisTemplate).delete(RedisKeys.wsConnectionKey(WS_SESSION_ID));
        verify(redisTemplate, never()).delete(RedisKeys.lobbyUserSessionKey(LOBBY_CODE, USER_IDENTIFIER));
        verify(redisTemplate, never()).delete(RedisKeys.lobbyUserSessionSequenceKey(LOBBY_CODE, USER_IDENTIFIER));
        verify(webSocketMetric).decrement();
    }

    private SessionDisconnectEvent disconnectEvent() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(WS_SESSION_ID);
        accessor.setSessionAttributes(Map.of(
                WebSocketHeaders.USER_IDENTIFIER,
                USER_IDENTIFIER
        ));

        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
        );

        return new SessionDisconnectEvent(
                this,
                message,
                WS_SESSION_ID,
                CloseStatus.SESSION_NOT_RELIABLE
        );
    }
}