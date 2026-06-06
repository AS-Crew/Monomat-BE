package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerInGameDisconnectEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerInGameReconnectEvent;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
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
    private RedisPublisher redisPublisher;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
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
                eventPublisher,
                redisPublisher,
                stringRedisTemplate,
                Duration.ofSeconds(5)
        );
    }

    @Test
    @DisplayName("인게임 이탈 이벤트 수신 시 이탈 안내 메시지 전송 및 5초 유예 타이머 등록")
    void handleInGameDisconnect_schedulesTimerAndBroadcasts() throws Exception {
        // given
        PlayerInGameDisconnectEvent event = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        // when
        gameDisconnectManager.handleInGameDisconnect(event);

        // then
        verify(nicknameResolver).resolveNicknameMap(eq(java.util.List.of(userIdentifier)));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(redisPublisher).publish(eq(io.github.ascrew.monomatbe.global.constant.StompDestinations.subscribeLobbyChat(lobbyCode)), any());
        verify(valueOperations).set(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier)), anyString(), any(Duration.class));
        verify(zSetOperations).add(eq(RedisKeys.gameDisconnectPendingZsetKey()), anyString(), anyDouble());
    }

    @Test
    @DisplayName("5초 내 재접속 시 유예 타이머 취소 및 복귀 메시지 전송")
    void cancelDisconnectTask_cancelsTimerAndBroadcasts() throws Exception {
        // given
        PlayerInGameDisconnectEvent event = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        // 이탈 먼저 처리하여 타이머 등록
        gameDisconnectManager.handleInGameDisconnect(event);

        // 복귀 시 토큰이 존재하는 상태 시뮬레이션
        when(valueOperations.get(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier)))).thenReturn("token-123");

        // when
        gameDisconnectManager.cancelDisconnectTask(lobbyCode, userIdentifier);

        // then
        verify(scheduledFuture).cancel(false);
        verify(redisPublisher, times(2)).publish(eq(io.github.ascrew.monomatbe.global.constant.StompDestinations.subscribeLobbyChat(lobbyCode)), any());
        verify(stringRedisTemplate, times(2)).delete(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier)));
        verify(zSetOperations).remove(eq(RedisKeys.gameDisconnectPendingZsetKey()), eq(lobbyCode + ":" + userIdentifier + ":token-123"));
    }

    @Test
    @DisplayName("로비 폭파 시 등록된 타이머들이 모두 취소된다")
    void handleLobbyClosed_cancelsAllLobbyTimers() {
        // given
        PlayerInGameDisconnectEvent event1 = new PlayerInGameDisconnectEvent(lobbyCode, "user1");
        PlayerInGameDisconnectEvent event2 = new PlayerInGameDisconnectEvent(lobbyCode, "user2");
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of("user1", "U1", "user2", "U2"));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        gameDisconnectManager.handleInGameDisconnect(event1);
        gameDisconnectManager.handleInGameDisconnect(event2);

        // 각 사용자의 토큰 시뮬레이션
        when(valueOperations.get(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, "user1")))).thenReturn("tok-u1");
        when(valueOperations.get(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, "user2")))).thenReturn("tok-u2");

        // when
        gameDisconnectManager.handleLobbyClosed(new LobbyClosedEvent(lobbyCode));

        // then
        verify(scheduledFuture, times(2)).cancel(false);
        verify(stringRedisTemplate, times(2)).delete(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, "user1")));
        verify(stringRedisTemplate, times(2)).delete(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, "user2")));
        verify(zSetOperations).remove(eq(RedisKeys.gameDisconnectPendingZsetKey()), eq(lobbyCode + ":user1:tok-u1"));
        verify(zSetOperations).remove(eq(RedisKeys.gameDisconnectPendingZsetKey()), eq(lobbyCode + ":user2:tok-u2"));
    }

    @Test
    @DisplayName("재연결 이벤트 수신 시 이탈 대기 타이머가 취소된다")
    void handleInGameReconnect_cancelsTimer() throws Exception {
        // given
        PlayerInGameDisconnectEvent disconnectEvent = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        // 이탈 처리
        gameDisconnectManager.handleInGameDisconnect(disconnectEvent);

        // 복귀 시 토큰이 존재하는 상태 시뮬레이션
        when(valueOperations.get(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier)))).thenReturn("token-123");

        // when
        PlayerInGameReconnectEvent reconnectEvent = new PlayerInGameReconnectEvent(lobbyCode, userIdentifier);
        gameDisconnectManager.handleInGameReconnect(reconnectEvent);

        // then
        verify(scheduledFuture).cancel(false);
        verify(redisPublisher, times(2)).publish(eq(io.github.ascrew.monomatbe.global.constant.StompDestinations.subscribeLobbyChat(lobbyCode)), any());
        verify(stringRedisTemplate, times(2)).delete(eq(RedisKeys.lobbyUserDisconnectTokenKey(lobbyCode, userIdentifier)));
        verify(zSetOperations).remove(eq(RedisKeys.gameDisconnectPendingZsetKey()), eq(lobbyCode + ":" + userIdentifier + ":token-123"));
    }

    @Test
    @DisplayName("이탈 유예 만료 시점 이전에 복귀하여 토큰이 취소된 경우, 스케줄 작업이 만료 시점에 도달해도 영구 퇴장 이벤트를 발행하지 않는다")
    void executePermanentLeave_withInvalidToken_ignoresEventPublishing() throws Exception {
        // given
        PlayerInGameDisconnectEvent disconnectEvent = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduledFuture).when(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // 이탈 처리
        gameDisconnectManager.handleInGameDisconnect(disconnectEvent);

        // Lua 스크립트 실행 결과로 0L(실패/불일치) 반환하도록 설정
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        // when: 스케줄러가 보관하던 람다를 직접 실행
        Runnable scheduledTask = runnableCaptor.getValue();
        scheduledTask.run();

        // then: PlayerLeaveEvent가 한 번도 발행되지 않아야 함
        verify(eventPublisher, never()).publishEvent(any(PlayerLeaveEvent.class));
    }

    @Test
    @DisplayName("이탈 유예 만료 시 Redis의 토큰이 일치하면, PlayerLeaveEvent를 먼저 발행한 후 LEAVE 메시지를 브로드캐스트한다")
    void executePermanentLeave_withValidToken_publishesLeaveEventThenBroadcasts() throws Exception {
        // given
        PlayerInGameDisconnectEvent disconnectEvent = new PlayerInGameDisconnectEvent(lobbyCode, userIdentifier);
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(scheduledFuture).when(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

        // 이탈 처리
        gameDisconnectManager.handleInGameDisconnect(disconnectEvent);

        // Lua 스크립트 실행 결과로 1L(성공) 반환하도록 설정
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        // when: 스케줄 작업 실행
        Runnable scheduledTask = runnableCaptor.getValue();
        scheduledTask.run();

        // then: PlayerLeaveEvent 발행 검증 후 순차적으로 Redis 브로드캐스트가 이뤄졌는지 검증
        org.mockito.InOrder inOrder = inOrder(eventPublisher, redisPublisher);
        inOrder.verify(eventPublisher).publishEvent(any(PlayerLeaveEvent.class));
        inOrder.verify(redisPublisher).publish(eq(StompDestinations.subscribeLobbyChat(lobbyCode)), any());
    }

    @Test
    @DisplayName("주기적 스케줄러가 만료된 이탈 유저들을 조회하여 영구 퇴장 처리한다")
    void processExpiredDisconnects_processesExpiredMembers() {
        // given
        String zsetKey = RedisKeys.gameDisconnectPendingZsetKey();
        String zsetMember = lobbyCode + ":" + userIdentifier + ":some-token-id";
        
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.rangeByScore(eq(zsetKey), eq(0.0), anyDouble())).thenReturn(java.util.Collections.singleton(zsetMember));
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(Map.of(userIdentifier, nickname));
        when(redisPublisher.publish(anyString(), any())).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), eq("some-token-id"), eq(zsetMember))).thenReturn(1L);

        // when
        gameDisconnectManager.processExpiredDisconnects();

        // then
        verify(eventPublisher).publishEvent(any(PlayerLeaveEvent.class));
        verify(redisPublisher).publish(eq(StompDestinations.subscribeLobbyChat(lobbyCode)), any());
    }
}
