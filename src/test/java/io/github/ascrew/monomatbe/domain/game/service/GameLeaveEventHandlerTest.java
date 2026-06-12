package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameLeaveEventHandlerTest {

    private static final String LOBBY_CODE = "LEAVE1";
    private static final String USER_IDENTIFIER = "user-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private GameSkipVoteService gameSkipVoteService;

    private GameLeaveEventHandler gameLeaveEventHandler;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        gameLeaveEventHandler = new GameLeaveEventHandler(redisTemplate, gameSkipVoteService);
    }

    @Test
    @DisplayName("퇴장 이벤트 처리 시 라운드 스킵 신호를 제거한 뒤 기준을 재평가한다")
    void playerLeaveRemovesRoundSignalsBeforeReevaluation() {
        // given
        givenSessionValues(List.of("PLAYING", "PLAYING", "2"));

        // when
        gameLeaveEventHandler.handlePlayerLeave(new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER));

        // then
        verify(gameSkipVoteService).removeParticipantRoundSignals(LOBBY_CODE, USER_IDENTIFIER, 2);
        verify(gameSkipVoteService).reevaluateSkipThresholds(LOBBY_CODE, 2);
    }

    @Test
    @DisplayName("multiGet 결과가 부족하면 후처리를 건너뛴다")
    void invalidHashValuesAreIgnored() {
        // given
        givenSessionValues(List.of("PLAYING"));

        // when
        gameLeaveEventHandler.handlePlayerLeave(new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER));

        // then
        verify(gameSkipVoteService, never()).removeParticipantRoundSignals(anyString(), anyString(), anyInt());
        verify(gameSkipVoteService, never()).reevaluateSkipThresholds(anyString(), anyInt());
    }

    @Test
    @DisplayName("현재 라운드 번호 파싱 실패 시 후처리를 건너뛴다")
    void invalidRoundNoIsIgnored() {
        // given
        givenSessionValues(List.of("PLAYING", "PLAYING", "not-number"));

        // when
        gameLeaveEventHandler.handlePlayerLeave(new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER));

        // then
        verify(gameSkipVoteService, never()).removeParticipantRoundSignals(anyString(), anyString(), anyInt());
        verify(gameSkipVoteService, never()).reevaluateSkipThresholds(anyString(), anyInt());
    }

    private void givenSessionValues(List<Object> values) {
        when(hashOperations.multiGet(
                RedisKeys.gameSessionKey(LOBBY_CODE),
                List.of(RedisKeys.FIELD_STATUS, RedisKeys.FIELD_ROUND_PHASE, RedisKeys.FIELD_CURRENT_ROUND_NO)
        )).thenReturn(values);
    }
}
