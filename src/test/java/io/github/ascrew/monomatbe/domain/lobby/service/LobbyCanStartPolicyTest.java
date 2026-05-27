package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPlayerResponse;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class LobbyCanStartPolicyTest {

    private static final Long MAP_ID = 10L;
    private static final String LOBBY_CODE = "TEST94";
    private static final String HOST_ID = "11111111-1111-1111-1111-111111111111";
    private static final String PLAYER_ID = "22222222-2222-2222-2222-222222222222";

    private final QuizMapJpaRepository quizMapJpaRepository =
            Mockito.mock(QuizMapJpaRepository.class);

    private final LobbyCanStartPolicy lobbyCanStartPolicy =
            new LobbyCanStartPolicy(quizMapJpaRepository);

    @Test
    @DisplayName("퇴장 이후 방장을 제외한 참여자가 없으면 canStart는 false다")
    void canStartFalseWhenOnlyHostRemainsAfterLeave() {
        // given
        JoinLobbyResponse lobbyInfo = waitingLobbyWithMap();

        GameLobby gameLobby = gameLobbyWithRoundCount(3);
        givenQuizMapSongCount(5);

        List<LobbyPlayerResponse> players = List.of(
                hostPlayer()
        );

        // when
        boolean canStart = lobbyCanStartPolicy.calculateCanStart(
                lobbyInfo,
                players,
                gameLobby
        );

        // then
        assertThat(canStart).isFalse();
    }

    @Test
    @DisplayName("퇴장 이후 남은 일반 참가자가 모두 ready 상태이면 canStart는 true다")
    void canStartTrueWhenRemainingNonHostPlayersAreReadyAfterLeave() {
        // given
        JoinLobbyResponse lobbyInfo = waitingLobbyWithMap();

        GameLobby gameLobby = gameLobbyWithRoundCount(3);
        givenQuizMapSongCount(5);

        List<LobbyPlayerResponse> players = List.of(
                hostPlayer(),
                readyPlayer(PLAYER_ID)
        );

        // when
        boolean canStart = lobbyCanStartPolicy.calculateCanStart(
                lobbyInfo,
                players,
                gameLobby
        );

        // then
        assertThat(canStart).isTrue();
    }

    @Test
    @DisplayName("퇴장 이후 남은 일반 참가자 중 ready가 아닌 유저가 있으면 canStart는 false다")
    void canStartFalseWhenRemainingNonHostPlayerIsNotReadyAfterLeave() {
        // given
        JoinLobbyResponse lobbyInfo = waitingLobbyWithMap();

        GameLobby gameLobby = gameLobbyWithRoundCount(3);
        givenQuizMapSongCount(5);

        List<LobbyPlayerResponse> players = List.of(
                hostPlayer(),
                notReadyPlayer(PLAYER_ID)
        );

        // when
        boolean canStart = lobbyCanStartPolicy.calculateCanStart(
                lobbyInfo,
                players,
                gameLobby
        );

        // then
        assertThat(canStart).isFalse();
    }

    @Test
    @DisplayName("맵 문제 수가 라운드 수보다 적으면 참여자가 ready여도 canStart는 false다")
    void canStartFalseWhenMapSongCountIsLessThanRoundCount() {
        // given
        JoinLobbyResponse lobbyInfo = waitingLobbyWithMap();

        GameLobby gameLobby = gameLobbyWithRoundCount(5);
        givenQuizMapSongCount(3);

        List<LobbyPlayerResponse> players = List.of(
                hostPlayer(),
                readyPlayer(PLAYER_ID)
        );

        // when
        boolean canStart = lobbyCanStartPolicy.calculateCanStart(
                lobbyInfo,
                players,
                gameLobby
        );

        // then
        assertThat(canStart).isFalse();
    }

    private JoinLobbyResponse waitingLobbyWithMap() {
        return new JoinLobbyResponse(
                LOBBY_CODE,
                "테스트 로비",
                HOST_ID,
                4,
                2,
                "WAITING",
                MAP_ID,
                "테스트 맵",
                "K-POP"
        );
    }

    private GameLobby gameLobbyWithRoundCount(int questionCount) {
        return GameLobby.builder()
                .id(1L)
                .mapId(MAP_ID)
                .inviteCode(LOBBY_CODE)
                .title("테스트 로비")
                .maxPlayers(4)
                .questionCount(questionCount)
                .timeLimitSeconds(30)
                .isPrivate(false)
                .status(LobbyStatus.WAITING)
                .isDeleted(false)
                .build();
    }

    private void givenQuizMapSongCount(int numOfSong) {
        QuizMap quizMap = QuizMap.builder()
                .id(MAP_ID)
                .title("테스트 맵")
                .description("테스트 맵 설명")
                .category(MapCategory.KPOP)
                .numOfSong(numOfSong)
                .totalPlayTime(180)
                .isPublic(true)
                .pendingPublic(false)
                .isDeleted(false)
                .build();

        when(quizMapJpaRepository.findById(MAP_ID))
                .thenReturn(Optional.of(quizMap));
    }

    private LobbyPlayerResponse hostPlayer() {
        return new LobbyPlayerResponse(
                HOST_ID,
                "host",
                true,
                false
        );
    }

    private LobbyPlayerResponse readyPlayer(String userIdentifier) {
        return new LobbyPlayerResponse(
                userIdentifier,
                "player",
                false,
                true
        );
    }

    private LobbyPlayerResponse notReadyPlayer(String userIdentifier) {
        return new LobbyPlayerResponse(
                userIdentifier,
                "player",
                false,
                false
        );
    }
}