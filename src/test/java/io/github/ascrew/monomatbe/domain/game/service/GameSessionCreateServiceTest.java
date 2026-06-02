package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus;
import io.github.ascrew.monomatbe.domain.game.exception.GameSessionAlreadyExistsException;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionPlayerJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameSessionCreateServiceTest {

    @Mock
    private GameSessionJpaRepository gameSessionJpaRepository;
    @Mock
    private GameSessionPlayerJpaRepository gameSessionPlayerJpaRepository;
    @Mock
    private MapItemJpaRepository mapItemJpaRepository;
    @Mock
    private LobbyRepository lobbyRepository;
    @Mock
    private GameParticipantResolver gameParticipantResolver;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisScript<String> initGameSessionScript;
    @Mock
    private JsonMapper jsonMapper;
    @Mock
    private GameSessionCleanupService gameSessionCleanupService;
    @Mock
    private io.github.ascrew.monomatbe.domain.game.config.GameSessionProperties gameSessionProperties;

    @InjectMocks
    private GameSessionCreateService gameSessionCreateService;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("정상적으로 게임 세션이 생성되고 라운드 시작 이벤트 payload에 정답이 포함되지 않는지 검증")
    void createGameSession_successAndValidatesPayload() {
        // given
        GameLobby lobby = GameLobby.builder()
                .inviteCode("ABC1234")
                .mapId(1L)
                .questionCount(1)
                .timeLimitSeconds(30)
                .status(LobbyStatus.PLAYING)
                .build();

        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(1).build();

        MapItem mapItem = MapItem.builder()
                .map(quizMap)
                .orderNum(1)
                .videoId("vId")
                .youtubeUrl("https://youtube.com/vId")
                .startTime(10)
                .endTime(20)
                .title("Secret Title")
                .artist("Secret Artist")
                .answers("[\"정답\"]")
                .hint("힌트")
                .build();

        User user = User.builder().id(1L).username("uId").userType(UserType.REGISTERED).build();

        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(mapItem));
        when(lobbyRepository.getParticipantIdentifiers("ABC1234"))
                .thenReturn(List.of("uId"));
        when(gameParticipantResolver.resolveUsers(List.of("uId")))
                .thenReturn(List.of(user));
        
        try {
            when(jsonMapper.readValue(eq("[\"정답\"]"), any(tools.jackson.core.type.TypeReference.class)))
                    .thenReturn(List.of("정답"));
            when(jsonMapper.writeValueAsString(any()))
                    .thenReturn("[\"정답\"]");
        } catch (Exception e) {
            // ignore for mock
        }
        
        when(redisTemplate.execute(
                eq(initGameSessionScript),
                any(List.class),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn("OK");

        // when
        RoundStartDto result = gameSessionCreateService.createGameSession(lobby, quizMap);

        // then
        // 1. DB Session 생성 확인
        ArgumentCaptor<GameSession> sessionCaptor = ArgumentCaptor.forClass(GameSession.class);
        verify(gameSessionJpaRepository).save(sessionCaptor.capture());
        GameSession savedSession = sessionCaptor.getValue();
        assertThat(savedSession.getCurrentRoundNo()).isEqualTo(1);
        assertThat(savedSession.getTotalQuestionCount()).isEqualTo(1);

        // 2. DB Player 생성 확인
        ArgumentCaptor<List<GameSessionPlayer>> playersCaptor = ArgumentCaptor.forClass(List.class);
        verify(gameSessionPlayerJpaRepository).saveAll(playersCaptor.capture());
        List<GameSessionPlayer> savedPlayers = playersCaptor.getValue();
        assertThat(savedPlayers).hasSize(1);
        assertThat(savedPlayers.get(0).getUser()).isEqualTo(user);

        // 3. Payload 필드 검증 (정답 및 민감정보 미포함, 신규 필드 포함)
        assertThat(result.type()).isEqualTo("ROUND_READY");
        assertThat(result.videoId()).isEqualTo("vId");
        assertThat(result.youtubeUrl()).isEqualTo("https://youtube.com/vId");
        assertThat(result.startTime()).isEqualTo(10);
        assertThat(result.endTime()).isEqualTo(40); // startTime(10) + timeLimitSeconds(30)
        assertThat(result.timeLimitSeconds()).isEqualTo(30);
        assertThat(result.roundNo()).isEqualTo(1);
        assertThat(result.serverStartedAt()).isGreaterThan(0L);

        // title, artist, answer 같은 필드가 DTO에 아예 존재하지 않음을 코드 구조상(record 정의) 보장됨.
    }

    @Test
    @DisplayName("Redis 세션 키가 이미 존재하여 ERROR_ALREADY_EXISTS가 반환될 때 GameSessionAlreadyExistsException 발생")
    void createGameSession_alreadyExistsThrowsException() {
        // given
        GameLobby lobby = GameLobby.builder()
                .inviteCode("ABC1234")
                .mapId(1L)
                .questionCount(1)
                .timeLimitSeconds(30)
                .status(LobbyStatus.PLAYING)
                .build();
        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(1).build();
        MapItem mapItem = MapItem.builder().map(quizMap).orderNum(1).videoId("vId").build();

        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L)).thenReturn(List.of(mapItem));
        when(lobbyRepository.getParticipantIdentifiers("ABC1234")).thenReturn(List.of("uId"));
        when(gameParticipantResolver.resolveUsers(List.of("uId"))).thenReturn(List.of(User.builder().id(1L).build()));

        when(redisTemplate.execute(
                eq(initGameSessionScript),
                any(List.class),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn("ERROR_ALREADY_EXISTS");

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gameSessionCreateService.createGameSession(lobby, quizMap))
                .isInstanceOf(GameSessionAlreadyExistsException.class)
                .hasMessage("게임 세션이 이미 존재합니다.");
    }

    @Test
    @DisplayName("정체되지 않은 active 세션이 있으면 GameSessionAlreadyExistsException으로 차단한다")
    void createGameSession_blocksWhenActiveSessionNotStale() {
        // given
        GameLobby lobby = GameLobby.builder()
                .inviteCode("ABC1234").mapId(1L).questionCount(1).timeLimitSeconds(30)
                .status(LobbyStatus.PLAYING).build();
        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(1).build();
        MapItem mapItem = MapItem.builder().map(quizMap).orderNum(1).videoId("vId").build();

        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L)).thenReturn(List.of(mapItem));

        // 1분 전에 시작된 세션 + 임계값 30분 → 정체 아님 → 차단
        GameSession active = GameSession.builder()
                .startedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1)).build();
        when(gameSessionProperties.getStaleThreshold()).thenReturn(java.time.Duration.ofMinutes(30));
        when(gameSessionJpaRepository.findActiveSessionByLobbyCode("ABC1234"))
                .thenReturn(java.util.Optional.of(active));

        // when & then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gameSessionCreateService.createGameSession(lobby, quizMap))
                .isInstanceOf(GameSessionAlreadyExistsException.class)
                .hasMessage("이미 진행 중인 게임 세션이 있습니다.");

        assertThat(active.getStatus()).isNotEqualTo(GameSessionStatus.FINISHED);
    }

    @Test
    @DisplayName("정체(stale)된 active 세션은 강제 종료·정리 후 새 게임 생성을 진행한다")
    void createGameSession_recoversStaleActiveSession() {
        // given
        GameLobby lobby = GameLobby.builder()
                .inviteCode("ABC1234").mapId(1L).questionCount(1).timeLimitSeconds(30)
                .status(LobbyStatus.PLAYING).build();
        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(1).build();
        MapItem mapItem = MapItem.builder()
                .map(quizMap).orderNum(1).videoId("vId").youtubeUrl("https://youtube.com/vId")
                .startTime(10).endTime(20).title("t").artist("a").answers("[\"정답\"]").build();
        User user = User.builder().id(1L).username("uId").userType(UserType.REGISTERED).build();

        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L)).thenReturn(List.of(mapItem));

        // 2시간 전에 시작된 세션 + 임계값 30분 → 정체 → 복구
        GameSession stale = GameSession.builder()
                .startedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2)).build();
        when(gameSessionProperties.getStaleThreshold()).thenReturn(java.time.Duration.ofMinutes(30));
        when(gameSessionJpaRepository.findActiveSessionByLobbyCode("ABC1234"))
                .thenReturn(java.util.Optional.of(stale));

        when(lobbyRepository.getParticipantIdentifiers("ABC1234")).thenReturn(List.of("uId"));
        when(gameParticipantResolver.resolveUsers(List.of("uId"))).thenReturn(List.of(user));
        try {
            when(jsonMapper.readValue(eq("[\"정답\"]"), any(tools.jackson.core.type.TypeReference.class)))
                    .thenReturn(List.of("정답"));
            when(jsonMapper.writeValueAsString(any())).thenReturn("[\"정답\"]");
        } catch (Exception e) {
            // ignore for mock
        }
        when(redisTemplate.execute(eq(initGameSessionScript), any(List.class),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("OK");

        // when
        RoundStartDto result = gameSessionCreateService.createGameSession(lobby, quizMap);

        // then - 정체 세션은 FINISHED 처리되고 Redis 잔존 키 정리(deleteNow)가 호출되며, 새 게임 생성이 진행된다.
        assertThat(stale.getStatus()).isEqualTo(GameSessionStatus.FINISHED);
        verify(gameSessionCleanupService).deleteNow("ABC1234");
        verify(gameSessionJpaRepository).save(any(GameSession.class));
        assertThat(result.roundNo()).isEqualTo(1);
    }
}
