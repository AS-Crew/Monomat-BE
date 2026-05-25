package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LobbyQueryService의 로비 상세 조회 응답 중 canStart 계산을 검증한다.
 *
 * [변경 배경]
 * Issue #78에서 LobbyService facade를 제거하고,
 * 로비 상세 조회 책임을 LobbyQueryService로 분리했다.
 *
 * canStart 계산 자체는 LobbyCanStartPolicy가 담당하지만,
 * 이 테스트는 "로비 상세 조회 결과에 canStart가 올바르게 반영되는지"를 검증하므로
 * LobbyQueryService 기준으로 유지한다.
 */
class LobbyQueryServiceCanStartTest {

    private LobbyRepository lobbyRepository;
    private GameLobbyJpaRepository gameLobbyJpaRepository;
    private QuizMapJpaRepository quizMapJpaRepository;
    private LobbyPlayerNicknameResolver lobbyPlayerNicknameResolver;
    private LobbyQueryService lobbyQueryService;

    @BeforeEach
    void setUp() {
        lobbyRepository = mock(LobbyRepository.class);
        gameLobbyJpaRepository = mock(GameLobbyJpaRepository.class);
        quizMapJpaRepository = mock(QuizMapJpaRepository.class);
        lobbyPlayerNicknameResolver = mock(LobbyPlayerNicknameResolver.class);

        LobbyCanStartPolicy lobbyCanStartPolicy = new LobbyCanStartPolicy(
                quizMapJpaRepository
        );

        lobbyQueryService = new LobbyQueryService(
                lobbyRepository,
                gameLobbyJpaRepository,
                lobbyCanStartPolicy,
                lobbyPlayerNicknameResolver
        );
    }

    @Test
    void canStart는_Lua와_동일하게_방장_제외_참여자가_모두_ready이면_true다() {
        String code = "ABC123";
        String hostId = "11111111-1111-1111-1111-111111111111";
        String guestId = "22222222-2222-2222-2222-222222222222";

        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(lobbyInfo(code, hostId)));
        when(lobbyRepository.isParticipant(code, hostId)).thenReturn(true);
        when(lobbyRepository.getParticipantIdentifiers(code)).thenReturn(List.of(hostId, guestId));
        when(lobbyRepository.getReadyParticipantIdentifiers(code)).thenReturn(Set.of(guestId));
        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostId, guestId))).thenReturn(Map.of(
                hostId, "방장",
                guestId, "참여자"
        ));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby()));
        when(quizMapJpaRepository.findById(2L)).thenReturn(Optional.of(quizMap(5)));

        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                new CustomPrincipal(1L, hostId, UserType.GUEST)
        );

        assertThat(response.canStart()).isTrue();
    }

    @Test
    void canStart는_참여자가_ready가_아니면_false다() {
        String code = "ABC123";
        String hostId = "11111111-1111-1111-1111-111111111111";
        String guestId = "22222222-2222-2222-2222-222222222222";

        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(lobbyInfo(code, hostId)));
        when(lobbyRepository.isParticipant(code, hostId)).thenReturn(true);
        when(lobbyRepository.getParticipantIdentifiers(code)).thenReturn(List.of(hostId, guestId));
        when(lobbyRepository.getReadyParticipantIdentifiers(code)).thenReturn(Set.of());
        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostId, guestId))).thenReturn(Map.of(
                hostId, "방장",
                guestId, "참여자"
        ));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby()));
        when(quizMapJpaRepository.findById(2L)).thenReturn(Optional.of(quizMap(5)));

        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                new CustomPrincipal(1L, hostId, UserType.GUEST)
        );

        assertThat(response.canStart()).isFalse();
    }

    @Test
    void canStart는_맵_문제수가_라운드수보다_적으면_false다() {
        String code = "ABC123";
        String hostId = "11111111-1111-1111-1111-111111111111";
        String guestId = "22222222-2222-2222-2222-222222222222";

        when(lobbyRepository.findByInviteCode(code)).thenReturn(Optional.of(lobbyInfo(code, hostId)));
        when(lobbyRepository.isParticipant(code, hostId)).thenReturn(true);
        when(lobbyRepository.getParticipantIdentifiers(code)).thenReturn(List.of(hostId, guestId));
        when(lobbyRepository.getReadyParticipantIdentifiers(code)).thenReturn(Set.of(guestId));
        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostId, guestId))).thenReturn(Map.of(
                hostId, "방장",
                guestId, "참여자"
        ));
        when(gameLobbyJpaRepository.findByInviteCode(code)).thenReturn(Optional.of(gameLobby()));
        when(quizMapJpaRepository.findById(2L)).thenReturn(Optional.of(quizMap(4)));

        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                new CustomPrincipal(1L, hostId, UserType.GUEST)
        );

        assertThat(response.canStart()).isFalse();
    }

    private JoinLobbyResponse lobbyInfo(String code, String hostId) {
        return JoinLobbyResponse.builder()
                .inviteCode(code)
                .title("테스트 로비")
                .hostId(hostId)
                .maxPlayers(4)
                .currentPlayers(2)
                .status("WAITING")
                .mapId(2L)
                .mapTitle("테스트 맵")
                .mapCategory("J-POP")
                .build();
    }

    private GameLobby gameLobby() {
        return GameLobby.builder()
                .inviteCode("ABC123")
                .mapId(2L)
                .title("테스트 로비")
                .maxPlayers(4)
                .roundCount(5)
                .timeLimitSeconds(30)
                .isPrivate(false)
                .build();
    }

    private QuizMap quizMap(int numOfSong) {
        return QuizMap.builder()
                .id(2L)
                .title("테스트 맵")
                .category(MapCategory.JPOP)
                .numOfSong(numOfSong)
                .totalPlayTime(300)
                .isPublic(true)
                .isDeleted(false)
                .build();
    }
}