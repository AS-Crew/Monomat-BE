package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus;
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

    @InjectMocks
    private GameSessionCreateService gameSessionCreateService;

    @Test
    @DisplayName("정상적으로 게임 세션이 생성되고 라운드 시작 이벤트 payload에 정답이 포함되지 않는지 검증")
    void createGameSession_successAndValidatesPayload() {
        // given
        GameLobby lobby = GameLobby.builder()
                .inviteCode("ABC1234")
                .mapId(1L)
                .roundCount(1)
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
                .answer("정답")
                .hint("힌트")
                .build();

        User user = User.builder().id(1L).username("uId").userType(UserType.REGISTERED).build();

        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(mapItem));
        when(lobbyRepository.getParticipantIdentifiers("ABC1234"))
                .thenReturn(List.of("uId"));
        when(gameParticipantResolver.resolveUsers(List.of("uId")))
                .thenReturn(List.of(user));
        
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
        assertThat(savedSession.getTotalRoundCount()).isEqualTo(1);

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
        assertThat(result.endTime()).isEqualTo(20);
        assertThat(result.timeLimitSeconds()).isEqualTo(30);
        assertThat(result.roundNo()).isEqualTo(1);
        assertThat(result.serverStartedAt()).isGreaterThan(0L);

        // title, artist, answer 같은 필드가 DTO에 아예 존재하지 않음을 코드 구조상(record 정의) 보장됨.
    }
}
