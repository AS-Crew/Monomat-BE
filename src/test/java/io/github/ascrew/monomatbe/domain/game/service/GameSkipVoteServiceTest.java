package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.PlaybackErrorReportDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundEndReason;
import io.github.ascrew.monomatbe.domain.game.dto.RoundSkipVoteDto;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GameSkipVoteServiceTest {

    private static final String LOBBY_CODE = "SKIP12";
    private static final String USER_1 = "user-1";
    private static final String USER_2 = "user-2";
    private static final int ROUND_NO = 1;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private GameRoundEndService gameRoundEndService;

    @Mock
    private GameRealtimeNotifier gameRealtimeNotifier;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private GameSkipVoteService gameSkipVoteService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(true);
        lenient().when(lobbyRepository.isParticipant(eq(LOBBY_CODE), anyString())).thenReturn(true);
        lenient().when(hashOperations.multiGet(
                eq(RedisKeys.gameSessionKey(LOBBY_CODE)),
                eq(List.of(RedisKeys.FIELD_STATUS, RedisKeys.FIELD_ROUND_PHASE, RedisKeys.FIELD_CURRENT_ROUND_NO))
        )).thenReturn(List.of("PLAYING", "PLAYING", "1"));

        gameSkipVoteService = new GameSkipVoteService(
                redisTemplate,
                lobbyRepository,
                gameRoundEndService,
                gameRealtimeNotifier
        );
    }

    @Test
    @DisplayName("중복 스킵 투표는 SADD 멱등 Set으로 기록되고 기준 미달이면 종료하지 않는다")
    void duplicateSkipVoteCountsOnce() {
        // given
        String skipVotesKey = RedisKeys.gameSessionRoundSkipVotesKey(LOBBY_CODE, ROUND_NO);
        givenVoteState(skipVotesKey, Set.of(USER_1), Set.of(USER_1, USER_2, "user-3"));

        // when
        gameSkipVoteService.voteSkip(LOBBY_CODE, USER_1, ROUND_NO);
        gameSkipVoteService.voteSkip(LOBBY_CODE, USER_1, ROUND_NO);

        // then
        verify(setOperations, times(2)).add(skipVotesKey, USER_1);
        verify(redisTemplate, times(2)).expire(skipVotesKey, Duration.ofHours(2));
        verify(gameRoundEndService, never()).endRound(anyString(), anyInt(), any(RoundEndReason.class));
        verify(gameRealtimeNotifier, times(2)).notifyRoundSkipVote(eq(LOBBY_CODE), any(RoundSkipVoteDto.class));
    }

    @Test
    @DisplayName("스킵 투표 기준 도달 시 SKIP_VOTE 사유로 라운드를 종료한다")
    void skipVoteThresholdEndsRound() {
        // given
        String skipVotesKey = RedisKeys.gameSessionRoundSkipVotesKey(LOBBY_CODE, ROUND_NO);
        when(setOperations.members(skipVotesKey)).thenReturn(Set.of(USER_1), Set.of(USER_1, USER_2));
        when(setOperations.members(RedisKeys.lobbyParticipantsKey(LOBBY_CODE))).thenReturn(Set.of(USER_1, USER_2, "user-3"));

        // when
        gameSkipVoteService.voteSkip(LOBBY_CODE, USER_1, ROUND_NO);
        gameSkipVoteService.voteSkip(LOBBY_CODE, USER_2, ROUND_NO);

        // then
        verify(gameRoundEndService).endRound(LOBBY_CODE, ROUND_NO, RoundEndReason.SKIP_VOTE);
    }

    @Test
    @DisplayName("방장 /p는 HOST_SKIP 사유로 라운드를 종료한다")
    void hostForceSkipEndsRound() {
        // given
        when(hashOperations.get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_HOST_USER_ID)).thenReturn(USER_1);

        // when
        boolean handled = gameSkipVoteService.forceSkipByHost(LOBBY_CODE, USER_1, ROUND_NO);

        // then
        assertThat(handled).isTrue();
        verify(gameRoundEndService).endRound(LOBBY_CODE, ROUND_NO, RoundEndReason.HOST_SKIP);
    }

    @Test
    @DisplayName("비방장 /p는 명령으로 처리하지 않는다")
    void nonHostForceSkipIsNotHandled() {
        // given
        when(hashOperations.get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_HOST_USER_ID)).thenReturn(USER_1);

        // when
        boolean handled = gameSkipVoteService.forceSkipByHost(LOBBY_CODE, USER_2, ROUND_NO);

        // then
        assertThat(handled).isFalse();
        verify(gameRoundEndService, never()).endRound(anyString(), anyInt(), any(RoundEndReason.class));
    }

    @Test
    @DisplayName("재생 오류 보고 기준 도달 시 모니터링 로그를 남기고 PLAYBACK_ERROR 사유로 종료한다")
    void playbackErrorThresholdEndsRound(CapturedOutput output) {
        // given
        String playbackErrorsKey = RedisKeys.gameSessionRoundPlaybackErrorsKey(LOBBY_CODE, ROUND_NO);
        when(setOperations.members(playbackErrorsKey)).thenReturn(Set.of(USER_1), Set.of(USER_1, USER_2));
        when(setOperations.members(RedisKeys.lobbyParticipantsKey(LOBBY_CODE))).thenReturn(Set.of(USER_1, USER_2, "user-3"));

        // when
        gameSkipVoteService.reportPlaybackError(
                LOBBY_CODE,
                USER_1,
                new PlaybackErrorReportDto(ROUND_NO, "150", "region blocked")
        );
        gameSkipVoteService.reportPlaybackError(
                LOBBY_CODE,
                USER_2,
                new PlaybackErrorReportDto(ROUND_NO, "150", "region blocked")
        );

        // then
        verify(gameRoundEndService).endRound(LOBBY_CODE, ROUND_NO, RoundEndReason.PLAYBACK_ERROR);
        assertThat(output).contains("[MONITORING_REQUIRED]");
    }

    @Test
    @DisplayName("지원하지 않는 재생 오류 코드는 집계하지 않는다")
    void unsupportedPlaybackErrorCodeIsIgnored() {
        // when
        gameSkipVoteService.reportPlaybackError(
                LOBBY_CODE,
                USER_1,
                new PlaybackErrorReportDto(ROUND_NO, "YOUTUBE_REGION_BLOCKED", "region blocked")
        );

        // then
        verify(setOperations, never()).add(RedisKeys.gameSessionRoundPlaybackErrorsKey(LOBBY_CODE, ROUND_NO), USER_1);
        verify(gameRoundEndService, never()).endRound(anyString(), anyInt(), any(RoundEndReason.class));
    }

    @Test
    @DisplayName("현재 참가자가 아닌 기존 표는 기준 계산에서 제외한다")
    void staleVotesAreExcludedFromThreshold() {
        // given
        String skipVotesKey = RedisKeys.gameSessionRoundSkipVotesKey(LOBBY_CODE, ROUND_NO);
        givenVoteState(skipVotesKey, Set.of(USER_1, "left-user"), Set.of(USER_1, USER_2, "user-3"));

        // when
        gameSkipVoteService.voteSkip(LOBBY_CODE, USER_1, ROUND_NO);

        // then
        verify(gameRoundEndService, never()).endRound(anyString(), anyInt(), any(RoundEndReason.class));
    }

    @Test
    @DisplayName("퇴장한 참가자의 스킵 투표와 재생 오류 보고를 라운드 Set에서 제거한다")
    void removeParticipantRoundSignalsRemovesSkipAndPlaybackEntries() {
        // when
        gameSkipVoteService.removeParticipantRoundSignals(LOBBY_CODE, USER_1, ROUND_NO);

        // then
        verify(setOperations).remove(RedisKeys.gameSessionRoundSkipVotesKey(LOBBY_CODE, ROUND_NO), USER_1);
        verify(setOperations).remove(RedisKeys.gameSessionRoundPlaybackErrorsKey(LOBBY_CODE, ROUND_NO), USER_1);
    }

    private void givenVoteState(String voteKey, Set<String> votes, Set<String> participants) {
        when(setOperations.members(voteKey)).thenReturn(votes);
        when(setOperations.members(RedisKeys.lobbyParticipantsKey(LOBBY_CODE))).thenReturn(participants);
    }
}
