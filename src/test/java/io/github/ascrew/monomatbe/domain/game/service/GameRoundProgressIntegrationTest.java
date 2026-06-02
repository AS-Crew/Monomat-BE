package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.domain.game.dto.RoundMetadataDto;
import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.dto.GameChatMessageDto;
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
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private GameRoundProgressService gameRoundProgressService;

    @Autowired
    private GameAnswerService gameAnswerService;

    @Autowired
    private GameLeaveEventHandler gameLeaveEventHandler;

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

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private LobbyRepository lobbyRepository;

    @MockitoBean
    private GameRoundStartService gameRoundStartService;

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
        when(nicknameResolver.fallbackNickname(USER_ID_1)).thenReturn("유저1");
        when(nicknameResolver.fallbackNickname(USER_ID_2)).thenReturn("유저2");
        when(nicknameResolver.fallbackNickname(USER_ID_3)).thenReturn("유저3");

        // MapItem mock
        MapItem mapItem = mock(MapItem.class);
        when(mapItem.getTitle()).thenReturn("Test Song");
        when(mapItem.getArtist()).thenReturn("Test Artist");
        when(mapItem.getThumbnailUrl()).thenReturn("http://test.com/thumb.jpg");
        when(mapItem.getAnswers()).thenReturn("[\"test answer\"]");
        when(mapItemJpaRepository.findById(anyLong())).thenReturn(Optional.of(mapItem));

        String roundsKey = RedisKeys.gameSessionRoundsKey(LOBBY_CODE);
        redisTemplate.opsForList().rightPushAll(roundsKey, "1001", "1002", "1003");

        when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(true);
        when(lobbyRepository.isParticipant(eq(LOBBY_CODE), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(RedisKeys.gameSessionPlayersKey(LOBBY_CODE));
        redisTemplate.delete(RedisKeys.gameSessionRoundsKey(LOBBY_CODE));
        redisTemplate.delete(RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundCorrectTimesKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundEndedLockKey(LOBBY_CODE, 1));
        redisTemplate.delete(RedisKeys.gameSessionRoundEndedLockKey(LOBBY_CODE, 3));
        redisTemplate.delete(RedisKeys.gameSessionNextRoundLockKey(LOBBY_CODE, 2));
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
        verify(lobby).changeStatus(LobbyStatus.FINISHED);
        verify(gameLobbyJpaRepository).save(lobby);

        assertThat(redisTemplate.opsForHash().get(RedisKeys.gameSessionKey(LOBBY_CODE), "status")).isEqualTo("FINISHED");
        assertThat(redisTemplate.opsForHash().get(RedisKeys.lobbyKey(LOBBY_CODE), "status")).isEqualTo("FINISHED");

        verify(lobbyRealtimeNotifier, times(1)).notifyLobbyInfoRefresh(eq(LOBBY_CODE), eq("SYSTEM"));
    }

    @Test
    @DisplayName("다음 라운드 시작(startNextRound) 시 세션 상태가 PLAYING이 되고 라운드 페이즈가 READY가 되며 ROUND_READY 이벤트가 브로드캐스트된다")
    void startNextRoundTransitionsToReadyAndBroadcasts() {
        // given
        // 2라운드 시작 시도
        
        // when
        gameRoundProgressService.startNextRound(LOBBY_CODE, 2);

        // then
        assertThat(gameSession.getCurrentRoundNo()).isEqualTo(2);

        String sessionKey = RedisKeys.gameSessionKey(LOBBY_CODE);
        assertThat(redisTemplate.opsForHash().get(sessionKey, RedisKeys.FIELD_CURRENT_ROUND_NO)).isEqualTo("2");
        assertThat(redisTemplate.opsForHash().get(sessionKey, RedisKeys.FIELD_STATUS)).isEqualTo("PLAYING");
        assertThat(redisTemplate.opsForHash().get(sessionKey, RedisKeys.FIELD_ROUND_PHASE)).isEqualTo("READY");

        ArgumentCaptor<RoundStartDto> captor = ArgumentCaptor.forClass(RoundStartDto.class);
        verify(gameRealtimeNotifier, times(1)).notifyRoundStart(eq(LOBBY_CODE), captor.capture());
        
        RoundStartDto captured = captor.getValue();
        assertThat(captured.type()).isEqualTo("ROUND_READY");
        assertThat(captured.roundNo()).isEqualTo(2);
        assertThat(captured.timeLimitSeconds()).isEqualTo(30);

        verify(gameRoundStartService, times(1)).scheduleForcePlaybackStart(eq(LOBBY_CODE), eq(2), eq(30));
    }

    @Test
    @DisplayName("플레이어 이탈 시 남은 플레이어들이 모두 정답 상태이면 라운드가 조기 종료된다")
    void playerLeaveTriggersEarlyRoundEndIfAllRemainingCorrect() {
        // given
        String sessionKey = RedisKeys.gameSessionKey(LOBBY_CODE);
        redisTemplate.opsForHash().put(sessionKey, "status", "PLAYING");
        redisTemplate.opsForHash().put(sessionKey, "round_phase", "PLAYING");
        redisTemplate.opsForHash().put(sessionKey, "current_round_no", "1");

        String participantsKey = RedisKeys.lobbyParticipantsKey(LOBBY_CODE);
        redisTemplate.opsForSet().add(participantsKey, USER_ID_1, USER_ID_2, USER_ID_3);

        String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1);
        redisTemplate.opsForSet().add(correctPlayersKey, USER_ID_1, USER_ID_2);

        PlayerLeaveEvent leaveEvent = new PlayerLeaveEvent(LOBBY_CODE, USER_ID_3);

        // when
        gameLeaveEventHandler.handlePlayerLeave(leaveEvent);

        // then
        String endedLockKey = RedisKeys.gameSessionRoundEndedLockKey(LOBBY_CODE, 1);
        assertThat(redisTemplate.hasKey(endedLockKey)).isTrue();
        
        verify(gameRealtimeNotifier, times(1)).notifyRoundEnd(eq(LOBBY_CODE), any(RoundMetadataDto.class));
    }

    @Test
    @DisplayName("라운드가 이미 종료되어 round_ended_at 필드가 존재하면 정답을 제출해도 ROUND_ALREADY_ENDED 처리되어 정답자로 등록되지 않고 마스킹 채팅으로 전파된다")
    void answerSubmissionBlockedIfRoundAlreadyEnded() {
        // given
        String sessionKey = RedisKeys.gameSessionKey(LOBBY_CODE);
        redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_STATUS, "PLAYING");
        redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_ROUND_PHASE, "PLAYING");
        redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_CURRENT_ROUND_NO, "1");
        redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_TIME_LIMIT_SECONDS, "30");
        
        redisTemplate.opsForHash().put(sessionKey, RedisKeys.gameSessionRoundPlaybackStartedAtField(1), String.valueOf(System.currentTimeMillis() - 5000));

        String roundDataKey = RedisKeys.gameSessionRoundDataKey(LOBBY_CODE, 1);
        redisTemplate.opsForHash().put(roundDataKey, "normalized_answers", "[\"testanswer\"]");

        redisTemplate.opsForHash().put(sessionKey, RedisKeys.gameSessionRoundEndedAtField(1), String.valueOf(System.currentTimeMillis() - 1000));

        GameChatMessageDto messageDto = new GameChatMessageDto(1, "test answer");

        // when
        gameAnswerService.processGameChat(LOBBY_CODE, USER_ID_1, messageDto);

        // then
        String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(LOBBY_CODE, 1);
        assertThat(redisTemplate.opsForSet().isMember(correctPlayersKey, USER_ID_1)).isFalse();

        ArgumentCaptor<ChatMessageDto> chatCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/game/" + LOBBY_CODE + "/chat"), chatCaptor.capture());
        
        ChatMessageDto capturedChat = chatCaptor.getValue();
        assertThat(capturedChat.getContent()).isEqualTo("***");
        assertThat(capturedChat.getSender()).isEqualTo("유저1");
    }
}
