package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerInGameDisconnectEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameDisconnectManagerTest {

    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private LobbyPlayerNicknameResolver nicknameResolver;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private JsonMapper pubSubJsonMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private GameDisconnectManager gameDisconnectManager;

    private final String lobbyCode = "LOB123";
    private final String userIdentifier = "user-uuid-1234";
    private final String nickname = "Tester";

    @BeforeEach
    void setUp() {
        gameDisconnectManager = new GameDisconnectManager(
                taskScheduler,
                nicknameResolver,
                messagingTemplate,
                pubSubJsonMapper,
                eventPublisher
        );
    }

    @Test
    @DisplayName("인게임 이탈 이벤트 수신 시 이탈 안내 메시지 전송 및 5초 유예 타이머 등록")
    void handleInGameDisconnect_schedulesTimerAndBroadcasts() throws Exception {
        // given
        PlayerInGameDisconnectEvent event = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(pubSubJsonMapper.writeValueAsString(any())).thenReturn("{}");
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        // when
        gameDisconnectManager.handleInGameDisconnect(event);

        // then
        verify(nicknameResolver).resolveNicknameMap(eq(java.util.List.of(userIdentifier)));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(messagingTemplate).convertAndSend(eq(io.github.ascrew.monomatbe.global.constant.StompDestinations.subscribeLobbyChat(lobbyCode)), anyString());
    }

    @Test
    @DisplayName("5초 내 재접속 시 유예 타이머 취소 및 복귀 메시지 전송")
    void cancelDisconnectTask_cancelsTimerAndBroadcasts() throws Exception {
        // given
        PlayerInGameDisconnectEvent event = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(pubSubJsonMapper.writeValueAsString(any())).thenReturn("{}");
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        // 이탈 먼저 처리하여 타이머 등록
        gameDisconnectManager.handleInGameDisconnect(event);

        // when
        gameDisconnectManager.cancelDisconnectTask(lobbyCode, userIdentifier);

        // then
        verify(scheduledFuture).cancel(false);
        verify(messagingTemplate, times(2)).convertAndSend(eq(io.github.ascrew.monomatbe.global.constant.StompDestinations.subscribeLobbyChat(lobbyCode)), anyString());
    }

    @Test
    @DisplayName("로비 폭파 시 등록된 타이머들이 모두 취소된다")
    void handleLobbyClosed_cancelsAllLobbyTimers() {
        // given
        PlayerInGameDisconnectEvent event1 = new PlayerInGameDisconnectEvent(lobbyCode, "user1");
        PlayerInGameDisconnectEvent event2 = new PlayerInGameDisconnectEvent(lobbyCode, "user2");
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of("user1", "U1", "user2", "U2"));
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        gameDisconnectManager.handleInGameDisconnect(event1);
        gameDisconnectManager.handleInGameDisconnect(event2);

        // when
        gameDisconnectManager.handleLobbyClosed(new LobbyClosedEvent(lobbyCode));

        // then
        verify(scheduledFuture, times(2)).cancel(false);
    }
}
