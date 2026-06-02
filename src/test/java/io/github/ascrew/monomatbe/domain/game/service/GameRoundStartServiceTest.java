package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundPlaybackStartedDto;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameRoundStartServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private RedisScript<String> readyToPlayScript;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private org.springframework.scheduling.TaskScheduler taskScheduler;
    @Mock
    private GameRoundProgressService gameRoundProgressService;

    private GameRoundStartService gameRoundStartService;

    private final String lobbyCode = "TEST12";
    private final String userIdentifier = "user-1";
    private final int roundNo = 1;

    @BeforeEach
    void setUp() {
        gameRoundStartService = new GameRoundStartService(redisTemplate, messagingTemplate, readyToPlayScript, taskScheduler, gameRoundProgressService);
    }

    @Test
    @DisplayName("정상적으로 모두 준비 완료된 경우 (ALL_READY 반환)")
    void processReadyToPlay_allReady() {
        // given
        when(redisTemplate.execute(eq(readyToPlayScript), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("ALL_READY");
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), eq("time_limit_seconds"))).thenReturn("30");
        when(hashOperations.putIfAbsent(anyString(), anyString(), anyString())).thenReturn(true);

        // when
        gameRoundStartService.processReadyToPlay(lobbyCode, userIdentifier, roundNo);

        // then
        ArgumentCaptor<RoundPlaybackStartedDto> captor = ArgumentCaptor.forClass(RoundPlaybackStartedDto.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/game/" + lobbyCode + "/round"), captor.capture());

        RoundPlaybackStartedDto dto = captor.getValue();
        assertThat(dto.type()).isEqualTo("ROUND_PLAYBACK_STARTED");
        assertThat(dto.roundNo()).isEqualTo(roundNo);
        assertThat(dto.durationSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("일부만 준비 완료된 경우 STOMP 발송하지 않음 (WAITING 반환)")
    void processReadyToPlay_waiting() {
        // given
        when(redisTemplate.execute(eq(readyToPlayScript), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("WAITING");

        // when
        gameRoundStartService.processReadyToPlay(lobbyCode, userIdentifier, roundNo);

        // then
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(RoundPlaybackStartedDto.class));
    }

    @Test
    @DisplayName("참여자가 아닌 경우 에러 반환 시 STOMP 발송하지 않음")
    void processReadyToPlay_notParticipant() {
        // given
        when(redisTemplate.execute(eq(readyToPlayScript), anyList(), anyString(), anyString(), anyString()))
                .thenReturn("ERROR_NOT_PARTICIPANT");

        // when
        gameRoundStartService.processReadyToPlay(lobbyCode, userIdentifier, roundNo);

        // then
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(RoundPlaybackStartedDto.class));
    }
}
