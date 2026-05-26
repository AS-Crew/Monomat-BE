package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.service.GameRealtimeNotifier;
import io.github.ascrew.monomatbe.domain.game.service.GameSessionCreateService;
import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LobbyStartServiceTest {

    @Mock
    private LobbyRepository lobbyRepository;
    @Mock
    private LobbyRealtimeNotifier lobbyRealtimeNotifier;
    @Mock
    private GameLobbyJpaRepository gameLobbyJpaRepository;
    @Mock
    private GameSessionCreateService gameSessionCreateService;
    @Mock
    private GameRealtimeNotifier gameRealtimeNotifier;
    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

    private LobbyStartService lobbyStartService;
    private LobbyStartPolicy lobbyStartPolicy;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        
        lobbyStartPolicy = new LobbyStartPolicy(quizMapJpaRepository);
        
        lobbyStartService = new LobbyStartService(
                lobbyRepository,
                lobbyRealtimeNotifier,
                gameLobbyJpaRepository,
                lobbyStartPolicy,
                gameSessionCreateService,
                gameRealtimeNotifier
        );
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("정상적으로 게임 세션 생성을 요청하고 성공한다")
    void startLobbyGame_success() {
        // given
        String code = "ABC1234";
        CustomPrincipal principal = new CustomPrincipal(1L, "uId", UserType.REGISTERED);
        
        GameLobby gameLobby = GameLobby.builder().inviteCode(code).mapId(1L).roundCount(5).build();
        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(10).isDeleted(false).build();
        
        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(mock(io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(1L)).thenReturn(Optional.of(quizMap));
        when(lobbyRepository.executeStartLobbyProcess(code, "uId")).thenReturn(new StartLobbyResult.Started(code));
        when(gameSessionCreateService.createGameSession(gameLobby, quizMap)).thenReturn(mock(RoundStartDto.class));

        // when
        lobbyStartService.startLobbyGame(code, principal);

        // then
        verify(gameLobbyJpaRepository).saveAndFlush(gameLobby);
        verify(gameSessionCreateService).createGameSession(gameLobby, quizMap);
    }

    @Test
    @DisplayName("맵이 없는 로비에서 시작 시 예외 발생")
    void startLobbyGame_failsWhenNoMap() {
        // given
        String code = "ABC1234";
        CustomPrincipal principal = new CustomPrincipal(1L, "uId", UserType.REGISTERED);
        
        // mapId가 없는 경우
        GameLobby gameLobby = GameLobby.builder().inviteCode(code).mapId(null).roundCount(5).build();
        
        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(mock(io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(code, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    @DisplayName("문제가 없는 맵(또는 요구 라운드 수보다 적은 맵)으로 시작 시 예외 발생")
    void startLobbyGame_failsWhenNotEnoughSongs() {
        // given
        String code = "ABC1234";
        CustomPrincipal principal = new CustomPrincipal(1L, "uId", UserType.REGISTERED);
        
        GameLobby gameLobby = GameLobby.builder().inviteCode(code).mapId(1L).roundCount(5).build();
        // 맵에 곡 수가 부족한 경우
        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(3).isDeleted(false).build();
        
        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(mock(io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(1L)).thenReturn(Optional.of(quizMap));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(code, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    @DisplayName("중복 게임 시작 요청 시 차단")
    void startLobbyGame_failsWhenDuplicateStart() {
        // given
        String code = "ABC1234";
        CustomPrincipal principal = new CustomPrincipal(1L, "uId", UserType.REGISTERED);
        
        GameLobby gameLobby = GameLobby.builder().inviteCode(code).mapId(1L).roundCount(5).build();
        QuizMap quizMap = QuizMap.builder().id(1L).numOfSong(10).isDeleted(false).build();
        
        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(mock(io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(1L)).thenReturn(Optional.of(quizMap));
        // 이미 진행 중인 로비이므로 LobbyNotWaiting 반환
        when(lobbyRepository.executeStartLobbyProcess(code, "uId")).thenReturn(new StartLobbyResult.LobbyNotWaiting(code));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(code, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
        
        verify(gameSessionCreateService, never()).createGameSession(any(), any());
    }
}
