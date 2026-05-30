package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.exception.GameSessionAlreadyExistsException;
import io.github.ascrew.monomatbe.domain.game.service.GameRealtimeNotifier;
import io.github.ascrew.monomatbe.domain.game.service.GameSessionCreateService;
import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ascrew.monomatbe.domain.game.service.GameRoundStartService;

@ExtendWith(MockitoExtension.class)
class LobbyStartServiceTest {

    private static final String LOBBY_CODE = "ABC1234";
    private static final String REQUESTER_IDENTIFIER = "uId";
    private static final Long REQUESTER_USER_ID = 1L;
    private static final Long MAP_ID = 1L;
    private static final int ROUND_COUNT = 5;

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

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
    private GameRoundStartService gameRoundStartService;

    private LobbyStartPolicy lobbyStartPolicy;

    private LobbyStartService lobbyStartService;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();

        lobbyStartPolicy = new LobbyStartPolicy(quizMapJpaRepository);

        lobbyStartService = new LobbyStartService(
                lobbyRepository,
                lobbyRealtimeNotifier,
                gameRealtimeNotifier,
                gameSessionCreateService,
                gameRoundStartService,
                gameLobbyJpaRepository,
                lobbyStartPolicy
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
        CustomPrincipal principal = registeredPrincipal();
        GameLobby gameLobby = startableLobby();
        QuizMap quizMap = startableMap();

        when(lobbyRepository.findByInviteCode(LOBBY_CODE))
                .thenReturn(Optional.of(mock(JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(LOBBY_CODE))
                .thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(MAP_ID))
                .thenReturn(Optional.of(quizMap));
        when(lobbyRepository.executeStartLobbyProcess(LOBBY_CODE, REQUESTER_IDENTIFIER))
                .thenReturn(new StartLobbyResult.Started(LOBBY_CODE));
        when(gameSessionCreateService.createGameSession(gameLobby, quizMap))
                .thenReturn(mock(RoundStartDto.class));

        // when
        lobbyStartService.startLobbyGame(LOBBY_CODE, principal);

        // then
        verify(gameLobbyJpaRepository).saveAndFlush(gameLobby);
        verify(gameSessionCreateService).createGameSession(gameLobby, quizMap);
    }

    @Test
    @DisplayName("맵이 없는 로비에서 시작 시 예외 발생")
    void startLobbyGame_failsWhenNoMap() {
        // given
        CustomPrincipal principal = registeredPrincipal();
        GameLobby gameLobby = GameLobby.builder()
                .inviteCode(LOBBY_CODE)
                .mapId(null)
                .questionCount(ROUND_COUNT)
                .build();

        when(lobbyRepository.findByInviteCode(LOBBY_CODE))
                .thenReturn(Optional.of(mock(JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(LOBBY_CODE))
                .thenReturn(Optional.of(gameLobby));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(LOBBY_CODE, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    @DisplayName("문제가 없는 맵 또는 요구 라운드 수보다 적은 맵으로 시작 시 예외 발생")
    void startLobbyGame_failsWhenNotEnoughSongs() {
        // given
        CustomPrincipal principal = registeredPrincipal();
        GameLobby gameLobby = startableLobby();
        QuizMap quizMap = QuizMap.builder()
                .id(MAP_ID)
                .numOfSong(ROUND_COUNT - 1)
                .isDeleted(false)
                .build();

        when(lobbyRepository.findByInviteCode(LOBBY_CODE))
                .thenReturn(Optional.of(mock(JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(LOBBY_CODE))
                .thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(MAP_ID))
                .thenReturn(Optional.of(quizMap));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(LOBBY_CODE, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    @DisplayName("중복 게임 시작 요청 시 차단")
    void startLobbyGame_failsWhenDuplicateStart() {
        // given
        CustomPrincipal principal = registeredPrincipal();
        GameLobby gameLobby = startableLobby();
        QuizMap quizMap = startableMap();

        when(lobbyRepository.findByInviteCode(LOBBY_CODE))
                .thenReturn(Optional.of(mock(JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(LOBBY_CODE))
                .thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(MAP_ID))
                .thenReturn(Optional.of(quizMap));
        when(lobbyRepository.executeStartLobbyProcess(LOBBY_CODE, REQUESTER_IDENTIFIER))
                .thenReturn(new StartLobbyResult.LobbyNotWaiting(LOBBY_CODE));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(LOBBY_CODE, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        verify(gameSessionCreateService, never()).createGameSession(any(), any());
    }

    @Test
    @DisplayName("이미 게임 세션이 존재하는 경우 예외(409) 발생 및 롤백 확인")
    void startLobbyGame_alreadyExistsSession() {
        // given
        CustomPrincipal principal = registeredPrincipal();
        GameLobby gameLobby = startableLobby();
        QuizMap quizMap = startableMap();

        when(lobbyRepository.findByInviteCode(LOBBY_CODE))
                .thenReturn(Optional.of(mock(JoinLobbyResponse.class)));
        when(gameLobbyJpaRepository.findByInviteCodeForUpdate(LOBBY_CODE))
                .thenReturn(Optional.of(gameLobby));
        when(quizMapJpaRepository.findById(MAP_ID))
                .thenReturn(Optional.of(quizMap));
        when(lobbyRepository.executeStartLobbyProcess(LOBBY_CODE, REQUESTER_IDENTIFIER))
                .thenReturn(new StartLobbyResult.Started(LOBBY_CODE));
        when(gameSessionCreateService.createGameSession(gameLobby, quizMap))
                .thenThrow(new GameSessionAlreadyExistsException("게임 세션이 이미 존재합니다."));

        // when & then
        assertThatThrownBy(() -> lobbyStartService.startLobbyGame(LOBBY_CODE, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        verify(lobbyRepository).rollbackStartedLobbyStatus(LOBBY_CODE);
    }

    private CustomPrincipal registeredPrincipal() {
        return new CustomPrincipal(
                REQUESTER_USER_ID,
                REQUESTER_IDENTIFIER,
                UserType.REGISTERED
        );
    }

    private GameLobby startableLobby() {
        return GameLobby.builder()
                .inviteCode(LOBBY_CODE)
                .mapId(MAP_ID)
                .questionCount(ROUND_COUNT)
                .build();
    }

    private QuizMap startableMap() {
        return QuizMap.builder()
                .id(MAP_ID)
                .numOfSong(ROUND_COUNT * 2)
                .isDeleted(false)
                .build();
    }
}