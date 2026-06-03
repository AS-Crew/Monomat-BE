package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LobbyCreateServiceTest {

    private static final Long HOST_USER_ID = 1L;
    private static final String HOST_IDENTIFIER = "host-session-id";
    private static final Long MAP_ID = 10L;
    private static final String INVITE_CODE = "ABC123";

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private GameLobbyJpaRepository gameLobbyJpaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LobbyMapPolicy lobbyMapPolicy;

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

    @InjectMocks
    private LobbyCreateService lobbyCreateService;

    @Test
    @DisplayName("맵 선택 상태에서 questionCount를 생략하고 맵 곡 수가 10개 이상이면 기본값 10개로 로비를 생성한다")
    void createsLobbyWithDefaultQuestionCountWhenSelectedMapHasEnoughSongs() {
        CreateLobbyRequest request = createRequest(MAP_ID, null);
        CustomPrincipal principal = createPrincipal();
        User host = createHost();
        LobbyMapMetadata mapMetadata = new LobbyMapMetadata(MAP_ID, "테스트 맵", "K-POP");
        QuizMap map = createMap(LobbyDefaults.DEFAULT_QUESTION_COUNT);

        when(userRepository.findById(HOST_USER_ID)).thenReturn(Optional.of(host));
        when(lobbyMapPolicy.resolveLobbyMapMetadata(MAP_ID, HOST_USER_ID)).thenReturn(mapMetadata);
        when(quizMapJpaRepository.findById(MAP_ID)).thenReturn(Optional.of(map));
        when(lobbyRepository.saveToRedis(
                eq(request),
                eq(HOST_IDENTIFIER),
                eq(mapMetadata),
                eq(LobbyDefaults.DEFAULT_QUESTION_COUNT),
                eq(LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS)
        )).thenReturn(INVITE_CODE);
        when(gameLobbyJpaRepository.save(any(GameLobby.class))).thenAnswer(invocation -> invocation.getArgument(0));

        lobbyCreateService.createLobby(request, principal);

        ArgumentCaptor<GameLobby> gameLobbyCaptor = ArgumentCaptor.forClass(GameLobby.class);
        verify(gameLobbyJpaRepository).save(gameLobbyCaptor.capture());

        GameLobby savedLobby = gameLobbyCaptor.getValue();
        assertThat(savedLobby.getQuestionCount()).isEqualTo(LobbyDefaults.DEFAULT_QUESTION_COUNT);
        assertThat(savedLobby.getMaxPlayers()).isEqualTo(LobbyDefaults.DEFAULT_MAX_PLAYERS);
        assertThat(savedLobby.getTimeLimitSeconds()).isEqualTo(LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("맵 선택 상태에서 questionCount를 생략했지만 맵 곡 수가 10개보다 적으면 400으로 실패한다")
    void failsWhenDefaultQuestionCountExceedsSelectedMapSongCount() {
        CreateLobbyRequest request = createRequest(MAP_ID, null);
        CustomPrincipal principal = createPrincipal();
        User host = createHost();
        LobbyMapMetadata mapMetadata = new LobbyMapMetadata(MAP_ID, "테스트 맵", "K-POP");
        QuizMap map = createMap(LobbyDefaults.DEFAULT_QUESTION_COUNT - 1);

        when(userRepository.findById(HOST_USER_ID)).thenReturn(Optional.of(host));
        when(lobbyMapPolicy.resolveLobbyMapMetadata(MAP_ID, HOST_USER_ID)).thenReturn(mapMetadata);
        when(quizMapJpaRepository.findById(MAP_ID)).thenReturn(Optional.of(map));

        assertThatThrownBy(() -> lobbyCreateService.createLobby(request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("설정한 문제 수(" + LobbyDefaults.DEFAULT_QUESTION_COUNT + ")")
                .hasMessageContaining("맵의 등록 곡 수(" + (LobbyDefaults.DEFAULT_QUESTION_COUNT - 1) + ")보다 많습니다.");

        verify(lobbyRepository, never()).saveToRedis(any(), any(), any(), any(Integer.class), any(Integer.class));
        verify(gameLobbyJpaRepository, never()).save(any(GameLobby.class));
    }

    @Test
    @DisplayName("맵 선택 상태에서 직접 지정한 questionCount가 맵 곡 수보다 크면 400으로 실패한다")
    void failsWhenRequestedQuestionCountExceedsSelectedMapSongCount() {
        int requestedQuestionCount = 7;
        int mapSongCount = 6;

        CreateLobbyRequest request = createRequest(MAP_ID, requestedQuestionCount);
        CustomPrincipal principal = createPrincipal();
        User host = createHost();
        LobbyMapMetadata mapMetadata = new LobbyMapMetadata(MAP_ID, "테스트 맵", "K-POP");
        QuizMap map = createMap(mapSongCount);

        when(userRepository.findById(HOST_USER_ID)).thenReturn(Optional.of(host));
        when(lobbyMapPolicy.resolveLobbyMapMetadata(MAP_ID, HOST_USER_ID)).thenReturn(mapMetadata);
        when(quizMapJpaRepository.findById(MAP_ID)).thenReturn(Optional.of(map));

        assertThatThrownBy(() -> lobbyCreateService.createLobby(request, principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("설정한 문제 수(" + requestedQuestionCount + ")")
                .hasMessageContaining("맵의 등록 곡 수(" + mapSongCount + ")보다 많습니다.");

        verify(lobbyRepository, never()).saveToRedis(any(), any(), any(), any(Integer.class), any(Integer.class));
        verify(gameLobbyJpaRepository, never()).save(any(GameLobby.class));
    }

    private static CreateLobbyRequest createRequest(Long mapId, Integer questionCount) {
        return new CreateLobbyRequest(
                "테스트 로비",
                null,
                false,
                mapId,
                questionCount,
                null
        );
    }

    private static CustomPrincipal createPrincipal() {
        return new CustomPrincipal(
                HOST_USER_ID,
                HOST_IDENTIFIER,
                UserType.REGISTERED,
                UserRole.USER
        );
    }

    private static User createHost() {
        return User.builder()
                .id(HOST_USER_ID)
                .username("host")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();
    }

    private static QuizMap createMap(int numOfSong) {
        return QuizMap.builder()
                .id(MAP_ID)
                .owner(createHost())
                .title("테스트 맵")
                .description("테스트 맵 설명")
                .category(MapCategory.KPOP)
                .numOfSong(numOfSong)
                .totalPlayTime(300)
                .isPublic(true)
                .pendingPublic(false)
                .isDeleted(false)
                .build();
    }
}