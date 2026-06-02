package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.domain.game.dto.RoundMetadataDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionPlayerJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyPlayerNicknameResolver;
import io.github.ascrew.monomatbe.domain.lobby.service.LobbyRealtimeNotifier;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class GameRoundProgressIntegrationTest {

    private static final String LOBBY_CODE = "PROGRESS12";
    private static final String USER_ID_1 = "player-1-uuid";
    private static final String USER_ID_2 = "player-2-uuid";
    private static final String USER_ID_3 = "player-3-uuid";

    @Autowired
    private GameRoundEndService gameRoundEndService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private GameSessionJpaRepository gameSessionJpaRepository;

    @MockitoBean
    private GameSessionPlayerJpaRepository gameSessionPlayerJpaRepository;

    @MockitoBean
    private GameLobbyJpaRepository gameLobbyJpaRepository;

    @MockitoBean
    private MapItemJpaRepository mapItemJpaRepository;

    @MockitoBean
    private UserSessionRepository userSessionRepository;

    @MockitoBean
    private GuestSessionRepository guestSessionRepository;

    @MockitoBean
    private LobbyPlayerNicknameResolver nicknameResolver;

    @MockitoBean
    private GameRealtimeNotifier gameRealtimeNotifier;

    @MockitoBean
    private LobbyRealtimeNotifier lobbyRealtimeNotifier;

    private GameLobby lobby;
    private GameSession gameSession;
    private List<GameSessionPlayer> players;

    @BeforeEach
    void setUp() {
        lobby = mock(GameLobby.class);
        when(lobby.getInviteCode()).thenReturn(LOBBY_CODE);
        when(lobby.getTimeLimitSeconds()).thenReturn(30);

        gameSession = GameSession.builder()
                .id(1L)
                .lobby(lobby)
                .currentRoundNo(1)
                .totalQuestionCount(3)
                .status(GameSessionStatus.READY)
                .build();

        when(gameSessionJpaRepository.findActiveSessionByLobbyCode(LOBBY_CODE))
                .thenReturn(Optional.of(gameSession));

        User user1 = mock(User.class);
        when(user1.getId()).thenReturn(101L);
        User user2 = mock(User.class);
        when(user2.getId()).thenReturn(102L);
        User user3 = mock(User.class);
        when(user3.getId()).thenReturn(103L);

        GameSessionPlayer p1 = GameSessionPlayer.builder().id(1L).gameSession(gameSession).user(user1).score(0).build();
        GameSessionPlayer p2 = GameSessionPlayer.builder().id(2L).gameSession(gameSession).user(user2).score(0).build();
        GameSessionPlayer p3 = GameSessionPlayer.builder().id(3L).gameSession(gameSession).user(user3).score(0).build();
        players = List.of(p1, p2, p3);

        when(gameSessionPlayerJpaRepository.findAllByGameSessionId(1L)).thenReturn(players);

        // Redis keys initialization
        String playersKey = RedisKeys.gameSessionPlayersKey(LOBBY_CODE);
        redisTemplate.opsForHash().put(playersKey, USER_ID_1, "0");
        redisTemplate.opsForHash().put(playersKey, USER_ID_2, "0");
        redisTemplate.opsForHash().put(playersKey, USER_ID_3, "0");

        // User sessions mocks
        UserSession s1 = mock(UserSession.class);
        when(s1.getUser()).thenReturn(user1);
        when(s1.getSessionId()).thenReturn(USER_ID_1);

        UserSession s2 = mock(UserSession.class);
        when(s2.getUser()).thenReturn(user2);
        when(s2.getSessionId()).thenReturn(USER_ID_2);

        UserSession s3 = mock(UserSession.class);
        when(s3.getUser()).thenReturn(user3);
        when(s3.getSessionId()).thenReturn(USER_ID_3);

        when(userSessionRepository.findBySessionIdIn(any())).thenReturn(List.of(s1, s2, s3));
        when(guestSessionRepository.findByGuestTokenIn(any())).thenReturn(Collections.emptyList());

        // Nicknames mock
        Map<String, String> nicknames = new HashMap<>();
        nicknames.put(USER_ID_1, "유저1");
        nicknames.put(USER_ID_2, "유저2");
        nicknames.put(USER_ID_3, "유저3");
        when(nicknameResolver.resolveNicknameMap(any())).thenReturn(nicknames);

        // MapItem mock
        MapItem mapItem = mock(MapItem.class);
        when(mapItem.getTitle()).thenReturn("Test Song");
        when(mapItem.getArtist()).thenReturn("Test Artist");
        when(mapItem.getThumbnailUrl()).thenReturn("http://test.com/thumb.jpg");
        when(mapItem.getAnswers()).thenReturn("[\"test answer\"]");
        when(mapItemJpaRepository.findById(anyLong())).thenReturn(Optional.of(mapItem));

        String roundsKey = RedisKeys.gameSessionRoundsKey(LOBBY_CODE);
        redisTemplate.opsForList().rightPushAll(roundsKey, "1001", "1002", "1003");
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(RedisKeys.gameSessionPlayersKey(LOBBY_CODE));
        redisTemplate.delete(RedisKeys.gameSessionRoundsKey(LOBBY_CODE));
        redisTemplate.delete(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundCorrectTimesKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundEndedLockKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundEndedLockKey(LOBBY_CODE, 3));
        redisTemplate.delete(RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, 3));
        redisTemplate.delete(RedisKeys.gameSessionKey(LOBBY_CODE));
    }

    @Test
    @DisplayName("라운드 종료 시 정답자들에게 기본 100점, 1등에게 추가 40점이 정상 부여된다")
    void endRoundCalculatesCorrectScores() {
        // given
        // 1번 플레이어가 1등, 2번 플레이어는 2등 정답, 3번 플레이어는 오답(제출 안함)
        String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1);
        redisTemplate.opsForSet().add(correctPlayersKey, USER_ID_1, USER_ID_2);

        String roundDataKey = RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, 1);
        redisTemplate.opsForHash().put(roundDataKey, "first_correct_user_id", USER_ID_1);

        String correctTimesKey = RedisKeys.gameSessionRoundCorrectTimesKey(LOBBY_CODE, 1);
        redisTemplate.opsForHash().put(correctTimesKey, USER_ID_1, String.valueOf(System.currentTimeMillis() - 5000));
        redisTemplate.opsForHash().put(correctTimesKey, USER_ID_2, String.valueOf(System.currentTimeMillis() - 2000));

        // when
        gameRoundEndService.endRound(LOBBY_CODE, 1);

        // then
        // 1등(USER_ID_1) -> 140점 득점
        // 2등(USER_ID_2) -> 100점 득점
        // 3등(USER_ID_3) -> 0점 득점
        assertThat(players.get(0).getScore()).isEqualTo(140);
        assertThat(players.get(1).getScore()).isEqualTo(100);
        assertThat(players.get(2).getScore()).isEqualTo(0);

        // Redis players score check
        String playersKey = RedisKeys.gameSessionPlayersKey(LOBBY_CODE);
        assertThat(redisTemplate.opsForHash().get(playersKey, USER_ID_1)).isEqualTo("140");
        assertThat(redisTemplate.opsForHash().get(playersKey, USER_ID_2)).isEqualTo("100");
        assertThat(redisTemplate.opsForHash().get(playersKey, USER_ID_3)).isEqualTo("0");

        // Event publish check
        verify(gameRealtimeNotifier, times(1)).notifyRoundEnd(eq(LOBBY_CODE), any(RoundMetadataDto.class));
    }

    @Test
    @DisplayName("마지막 라운드 종료 시 게임 세션 및 로비가 FINISHED 상태로 전환된다")
    void lastRoundTransitionsToFinished() {
        // given
        gameSession = GameSession.builder()
                .id(1L)
                .lobby(lobby)
                .currentRoundNo(3)
                .totalQuestionCount(3)
                .status(GameSessionStatus.READY)
                .build();
        when(gameSessionJpaRepository.findActiveSessionByLobbyCode(LOBBY_CODE)).thenReturn(Optional.of(gameSession));

        String roundDataKey = RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, 3);
        redisTemplate.opsForHash().put(roundDataKey, "first_correct_user_id", USER_ID_1);

        // when
        gameRoundEndService.endRound(LOBBY_CODE, 3);

        // then
        assertThat(gameSession.getStatus()).isEqualTo(GameSessionStatus.FINISHED);
        // DB 상태 변경은 Dirty Checking으로 커밋 시 반영되므로 명시적 save 호출은 없다.
        // (상태 전환 자체는 changeStatus 호출로 검증한다)
        verify(lobby).changeStatus(LobbyStatus.FINISHED);

        assertThat(redisTemplate.opsForHash().get(RedisKeys.gameSessionKey(LOBBY_CODE), "status")).isEqualTo("FINISHED");
        assertThat(redisTemplate.opsForHash().get(RedisKeys.lobbyKey(LOBBY_CODE), "status")).isEqualTo("FINISHED");

        verify(lobbyRealtimeNotifier, times(1)).notifyLobbyInfoRefresh(eq(LOBBY_CODE), eq("SYSTEM"));
    }
}
