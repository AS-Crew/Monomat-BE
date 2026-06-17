package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.CurrentRoundStatusResponse;
import io.github.ascrew.monomatbe.domain.lobby.LobbyUserAccessStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameSessionQueryServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private LobbyRepository lobbyRepository;
    @Mock
    private GameRoundEndService gameRoundEndService;
    @Mock
    private GameRoundProgressService gameRoundProgressService;
    @Mock
    private MapItemJpaRepository mapItemJpaRepository;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ListOperations<String, String> listOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    private GameSessionQueryService gameSessionQueryService;

    private final String lobbyCode = "ABC123";
    private final String userIdentifier = "user-uuid";

    @BeforeEach
    void setUp() {
        gameSessionQueryService = new GameSessionQueryService(
                redisTemplate,
                lobbyRepository,
                gameRoundEndService,
                gameRoundProgressService,
                mapItemJpaRepository
        );
    }

    @Test
    @DisplayName("로비를 찾을 수 없는 경우 404 예외가 발생한다")
    void getStatus_lobbyNotFound() {
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.LOBBY_NOT_FOUND);

        assertThatThrownBy(() -> gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND)
                .hasMessageContaining("존재하지 않는 로비입니다.");
    }

    @Test
    @DisplayName("강퇴당한 유저인 경우 403 예외가 발생한다")
    void getStatus_kickedUser() {
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.KICKED);

        assertThatThrownBy(() -> gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN)
                .hasMessageContaining("강퇴된 로비의 게임 상태는 조회할 수 없습니다.");
    }

    @Test
    @DisplayName("로비 참여자가 아닌 경우 403 예외가 발생한다")
    void getStatus_notParticipant() {
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.NOT_PARTICIPANT);

        assertThatThrownBy(() -> gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN)
                .hasMessageContaining("로비 참여자만 게임 상태를 조회할 수 있습니다.");
    }

    @Test
    @DisplayName("진행 중인 게임 세션이 Redis에 없는 경우 404 예외가 발생한다")
    void getStatus_noGameSessionInRedis() {
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(anyString(), anyList())).thenReturn(java.util.Arrays.asList(null, null, null, null));

        assertThatThrownBy(() -> gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND)
                .hasMessageContaining("진행 중인 게임 세션이 없습니다.");
    }

    @Test
    @DisplayName("READY 페이즈일 때 비디오 정보를 포함하고 WAITING 상태를 반환한다")
    void getStatus_readyPhase() {
        // given
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(RedisKeys.gameSessionKey(lobbyCode), List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ))).thenReturn(List.of("1", "30", "PLAYING", "READY"));

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.index(RedisKeys.gameSessionRoundsKey(lobbyCode), 0)).thenReturn("10");

        MapItem mapItem = MapItem.builder()
                .videoId("vid_123")
                .youtubeUrl("https://youtube.com/watch?v=vid_123")
                .startTime(15)
                .build();
        when(mapItemJpaRepository.findById(10L)).thenReturn(Optional.of(mapItem));

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(RedisKeys.gameSessionRoundCorrectPlayersKey(lobbyCode, 1), userIdentifier))
                .thenReturn(false);

        // when
        CurrentRoundStatusResponse response = gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier);

        // then
        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.roundPhase()).isEqualTo("READY");
        assertThat(response.videoId()).isEqualTo("vid_123");
        assertThat(response.youtubeUrl()).isEqualTo("https://youtube.com/watch?v=vid_123");
        assertThat(response.startTime()).isEqualTo(15);
        assertThat(response.remainingSeconds()).isNull();
        assertThat(response.isCorrect()).isFalse();
    }

    @Test
    @DisplayName("PLAYING 페이즈일 때 비디오 정보, 남은 시간, 정답 여부를 포함하여 반환한다")
    void getStatus_playingPhase() {
        // given
        long startTimeMillis = System.currentTimeMillis() - 10000; // 10초 경과
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(RedisKeys.gameSessionKey(lobbyCode), List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ))).thenReturn(List.of("1", "30", "PLAYING", "PLAYING"));

        when(hashOperations.get(RedisKeys.gameSessionKey(lobbyCode), RedisKeys.gameSessionRoundPlaybackStartedAtField(1)))
                .thenReturn(String.valueOf(startTimeMillis));

        when(redisTemplate.hasKey(RedisKeys.gameSessionPlaybackLockKey(lobbyCode, 1))).thenReturn(true);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.index(RedisKeys.gameSessionRoundsKey(lobbyCode), 0)).thenReturn("10");

        MapItem mapItem = MapItem.builder()
                .videoId("vid_123")
                .youtubeUrl("https://youtube.com/watch?v=vid_123")
                .startTime(15)
                .build();
        when(mapItemJpaRepository.findById(10L)).thenReturn(Optional.of(mapItem));

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(RedisKeys.gameSessionRoundCorrectPlayersKey(lobbyCode, 1), userIdentifier))
                .thenReturn(true);

        // when
        CurrentRoundStatusResponse response = gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier);

        // then
        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("PLAYING");
        assertThat(response.roundPhase()).isEqualTo("PLAYING");
        assertThat(response.videoId()).isEqualTo("vid_123");
        assertThat(response.startTime()).isEqualTo(15);
        assertThat(response.serverStartedAt()).isEqualTo(startTimeMillis);
        assertThat(response.remainingSeconds()).isLessThanOrEqualTo(20);
        assertThat(response.isCorrect()).isTrue();
    }

    @Test
    @DisplayName("ENDED 페이즈일 때 비디오 정보는 null이고 WAITING 상태를 반환한다")
    void getStatus_endedPhase() {
        // given
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(RedisKeys.gameSessionKey(lobbyCode), List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ))).thenReturn(List.of("1", "30", "PLAYING", "ENDED"));

        // when
        CurrentRoundStatusResponse response = gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier);

        // then
        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.roundPhase()).isEqualTo("ENDED");
        assertThat(response.videoId()).isNull();
        assertThat(response.isCorrect()).isFalse();
    }

    @Test
    @DisplayName("FINISHED 상태일 때 FINISHED 상태를 반환하고 비디오 정보는 null이다")
    void getStatus_finishedState() {
        // given
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(RedisKeys.gameSessionKey(lobbyCode), List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ))).thenReturn(List.of("5", "30", "FINISHED", "FINISHED"));

        // when
        CurrentRoundStatusResponse response = gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier);

        // then
        assertThat(response.roundNo()).isEqualTo(5);
        assertThat(response.status()).isEqualTo("FINISHED");
        assertThat(response.roundPhase()).isEqualTo("FINISHED");
        assertThat(response.videoId()).isNull();
    }

    @Test
    @DisplayName("타이머 유실 복구: 시간 초과가 발생한 경우 즉시 조기 종료 처리한다")
    void getStatus_timerHealing_timeout() {
        // given
        long startTimeMillis = System.currentTimeMillis() - 35000; // 35초 경과 (제한시간 30초 초과)
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(RedisKeys.gameSessionKey(lobbyCode), List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ))).thenReturn(List.of("1", "30", "PLAYING", "PLAYING"));

        when(hashOperations.get(RedisKeys.gameSessionKey(lobbyCode), RedisKeys.gameSessionRoundPlaybackStartedAtField(1)))
                .thenReturn(String.valueOf(startTimeMillis));

        // when
        CurrentRoundStatusResponse response = gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier);

        // then
        verify(gameRoundEndService).endRound(lobbyCode, 1);
        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.roundPhase()).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("타이머 유실 복구: 타이머 미등록인 경우 타이머를 재등록한다")
    void getStatus_timerHealing_reschedule() {
        // given
        long startTimeMillis = System.currentTimeMillis() - 10000; // 10초 경과 (제한시간 30초)
        when(lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(RedisKeys.gameSessionKey(lobbyCode), List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ))).thenReturn(List.of("1", "30", "PLAYING", "PLAYING"));

        when(hashOperations.get(RedisKeys.gameSessionKey(lobbyCode), RedisKeys.gameSessionRoundPlaybackStartedAtField(1)))
                .thenReturn(String.valueOf(startTimeMillis));

        when(gameRoundProgressService.isRoundEndScheduled(lobbyCode, 1)).thenReturn(false);

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.index(RedisKeys.gameSessionRoundsKey(lobbyCode), 0)).thenReturn("10");

        MapItem mapItem = MapItem.builder()
                .videoId("vid_123")
                .build();
        when(mapItemJpaRepository.findById(10L)).thenReturn(Optional.of(mapItem));
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        // when
        gameSessionQueryService.getCurrentRoundStatus(lobbyCode, userIdentifier);

        // then
        verify(gameRoundProgressService).rescheduleRoundEnd(eq(lobbyCode), eq(1), anyLong());
    }
}
