package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyListItemResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPlayerResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbySearchCondition;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LobbyQueryService의 공개 로비 목록 조회 정책과 로비 상세 조회 응답 조립을 검증한다.
 *
 * [테스트 범위]
 * - Redis 조회 자체는 LobbyRepository의 책임이므로 mock 처리한다.
 * - 이 테스트는 Service 계층의 목록 노출 정책과 상세 응답 조립 정책만 검증한다.
 *
 * [검증 정책]
 * - 비공개 로비가 공개 목록 원본에 섞여 있으면 제외한다.
 * - WAITING / PLAYING 로비는 공개 목록에 노출한다.
 * - FINISHED 로비는 목록에서 제외한다.
 * - 제목 검색은 대소문자를 무시하고 contains 기준으로 동작한다.
 * - 검색어는 LobbySearchCondition 생성 시점에 lower-case로 정규화된다.
 * - 카테고리 필터는 Repository에서 정규화된 FE 표시값(K-POP, J-POP, POP)을 기준으로 동작한다.
 * - 정원/현재 인원 값이 유효하지 않은 로비는 제외한다.
 * - 정렬은 최신순, 인원 많은 순, 빈자리 많은 순을 지원한다.
 * - 로비 상세 조회 응답의 players에는 userIdentifier, nickname, host, ready가 포함된다.
 */
class LobbyQueryServiceTest {

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final GameLobbyJpaRepository gameLobbyJpaRepository = mock(GameLobbyJpaRepository.class);
    private final LobbyCanStartPolicy lobbyCanStartPolicy = mock(LobbyCanStartPolicy.class);
    private final LobbyPlayerNicknameResolver lobbyPlayerNicknameResolver = mock(LobbyPlayerNicknameResolver.class);
    private final QuizMapJpaRepository quizMapJpaRepository = mock(QuizMapJpaRepository.class);

    private final LobbyQueryService lobbyQueryService = new LobbyQueryService(
            lobbyRepository,
            gameLobbyJpaRepository,
            lobbyCanStartPolicy,
            lobbyPlayerNicknameResolver,
            quizMapJpaRepository
    );

    @Test
    @DisplayName("공개 로비 목록 조회 시 WAITING과 PLAYING 상태 로비를 반환하고 FINISHED는 제외한다")
    void getPublicLobbies_filtersVisibleLobbyStatuses() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("WAITING-1", "대기 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("PLAYING-1", "진행 로비", "K-POP", 4, 6, LobbyStatus.PLAYING, 2000L),
                lobby("FINISHED-1", "종료 로비", "K-POP", 6, 6, LobbyStatus.FINISHED, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("WAITING-1", "PLAYING-1");
    }

    @Test
    @DisplayName("비공개 로비가 공개 로비 목록 원본에 섞여 있으면 제외한다")
    void getPublicLobbies_excludesPrivateLobby() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("PUBLIC", "공개 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L, false),
                lobby("PRIVATE", "비공개 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L, true)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("PUBLIC");
    }

    @Test
    @DisplayName("keyword가 있으면 로비 제목 기준으로 검색한다")
    void getPublicLobbies_filtersByKeyword() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("LOBBY-1", "KPOP 랜덤 퀴즈", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("LOBBY-2", "JPOP 애니송 퀴즈", "J-POP", 2, 6, LobbyStatus.WAITING, 2000L),
                lobby("LOBBY-3", "POP 히트곡 퀴즈", "POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of("애니송", null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("LOBBY-2");
    }

    @Test
    @DisplayName("keyword 검색은 대소문자를 구분하지 않는다")
    void getPublicLobbies_filtersByKeywordIgnoringCase() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("LOBBY-1", "KPOP Random Quiz", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("LOBBY-2", "JPOP Anime Quiz", "J-POP", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of("random", null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("LOBBY-1");
    }

    @Test
    @DisplayName("keyword 검색어의 앞뒤 공백은 제거된다")
    void getPublicLobbies_trimsKeyword() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("LOBBY-1", "KPOP Random Quiz", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("LOBBY-2", "JPOP Anime Quiz", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of("  random  ", null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("LOBBY-1");
    }

    @Test
    @DisplayName("mapCategory가 있으면 선택된 맵 카테고리 기준으로 필터링한다")
    void getPublicLobbies_filtersByMapCategory() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("LOBBY-1", "케이팝", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("LOBBY-2", "제이팝", "J-POP", 2, 6, LobbyStatus.WAITING, 2000L),
                lobby("LOBBY-3", "팝", "POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, "J-POP", "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("LOBBY-2");
    }

    @Test
    @DisplayName("카테고리 필터 적용 시 맵이 선택되지 않은 로비는 제외한다")
    void getPublicLobbies_excludesLobbyWithoutMapCategoryWhenCategoryFilterExists() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("MATCHED", "카테고리 일치 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("NO-MAP", "맵 미선택 로비", null, 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, "K-POP", "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("MATCHED");
    }

    @Test
    @DisplayName("latest 정렬은 생성 시각 내림차순으로 정렬한다")
    void getPublicLobbies_sortsByLatest() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("OLD", "오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L),
                lobby("NEW", "최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("MID", "중간 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("NEW", "MID", "OLD");
    }

    @Test
    @DisplayName("공개 로비 목록 페이징 조회는 정렬된 결과를 page와 size 기준으로 잘라 반환한다")
    void getPublicLobbyPage_returnsPagedItemsAfterSorting() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("OLD", "오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L),
                lobby("NEW", "최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 4000L),
                lobby("MID-2", "중간 로비 2", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("MID-1", "중간 로비 1", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 1, 2);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("MID-1", "OLD");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("공개 로비 목록 페이징 조회는 다음 페이지가 있으면 hasNext=true를 반환한다")
    void getPublicLobbyPage_returnsHasNextTrueWhenNextPageExists() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("LOBBY-5", "로비 5", "K-POP", 2, 6, LobbyStatus.WAITING, 5000L),
                lobby("LOBBY-4", "로비 4", "K-POP", 2, 6, LobbyStatus.WAITING, 4000L),
                lobby("LOBBY-3", "로비 3", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("LOBBY-2", "로비 2", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L),
                lobby("LOBBY-1", "로비 1", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 2);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("LOBBY-5", "LOBBY-4");
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    @DisplayName("공개 로비 목록 페이징 조회에서 범위를 초과한 page는 빈 items를 반환한다")
    void getPublicLobbyPage_returnsEmptyItemsWhenPageExceedsRange() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("LOBBY-1", "로비 1", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 10, 20);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(10);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("공개 로비 목록 페이징 조회는 정식 회원/게스트 방장 닉네임을 각각 채워 응답한다")
    void getPublicLobbyPage_fillsHostNicknameForMemberAndGuestHosts() {
        // given
        String memberHostId = "member-session-id";
        String guestHostId = "guest-token-id";

        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobbyWithHost("ROOM-1", memberHostId, 2000L),
                lobbyWithHost("ROOM-2", guestHostId, 1000L)
        ));

        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(memberHostId, guestHostId)))
                .thenReturn(Map.of(
                        memberHostId, "정식회원닉",
                        guestHostId, "게스트닉"
                ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 20);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(
                        LobbyListItemResponse::code,
                        LobbyListItemResponse::hostNickname
                )
                .containsExactly(
                        tuple("ROOM-1", "정식회원닉"),
                        tuple("ROOM-2", "게스트닉")
                );

        verify(lobbyPlayerNicknameResolver, times(1)).resolveNicknameMap(any());
    }

    @Test
    @DisplayName("방장 닉네임을 찾지 못하면 fallback 닉네임을 hostNickname으로 내려준다")
    void getPublicLobbyPage_usesFallbackNicknameWhenHostNicknameMissing() {
        // given
        String hostId = "missing-host-id";

        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobbyWithHost("ROOM-1", hostId, 1000L)
        ));
        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostId)))
                .thenReturn(Map.of());
        when(lobbyPlayerNicknameResolver.fallbackNickname(hostId))
                .thenReturn("Unknown-missin");

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 20);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::hostNickname)
                .containsExactly("Unknown-missin");
    }

    @Test
    @DisplayName("latest 정렬에서 생성 시각이 없는 기존 Redis 데이터는 후순위로 정렬한다")
    void getPublicLobbies_sortsLobbyWithoutCreatedAtLastByLatest() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("NO-CREATED-AT", "과거 데이터 로비", "K-POP", 2, 6, LobbyStatus.WAITING, null),
                lobby("NEW", "최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("OLD", "오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("NEW", "OLD", "NO-CREATED-AT");
    }

    @Test
    @DisplayName("most_players 정렬은 현재 인원 많은 순으로 정렬하고 동률이면 최신순으로 정렬한다")
    void getPublicLobbies_sortsByMostPlayers() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("TWO", "2명 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("FOUR-OLD", "4명 오래된 로비", "K-POP", 4, 6, LobbyStatus.WAITING, 1000L),
                lobby("FOUR-NEW", "4명 최신 로비", "K-POP", 4, 6, LobbyStatus.WAITING, 4000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "most_players");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("FOUR-NEW", "FOUR-OLD", "TWO");
    }

    @Test
    @DisplayName("most_available 정렬은 빈자리 많은 순으로 정렬하고 동률이면 최신순으로 정렬한다")
    void getPublicLobbies_sortsByMostAvailable() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("ONE-SEAT", "빈자리 1개", "K-POP", 5, 6, LobbyStatus.WAITING, 3000L),
                lobby("FOUR-SEATS-OLD", "빈자리 4개 오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L),
                lobby("FOUR-SEATS-NEW", "빈자리 4개 최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 4000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "most_available");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("FOUR-SEATS-NEW", "FOUR-SEATS-OLD", "ONE-SEAT");
    }

    @Test
    @DisplayName("currentPlayers가 maxPlayers보다 크면 공개 로비 목록에서 제외한다")
    void getPublicLobbies_excludesLobbyWhenCurrentPlayersExceedsMaxPlayers() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("BROKEN", "손상 데이터 로비", "K-POP", 8, 6, LobbyStatus.WAITING, 4000L),
                lobby("NORMAL", "정상 로비", "K-POP", 5, 6, LobbyStatus.WAITING, 3000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "most_available");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("NORMAL");
    }

    @Test
    @DisplayName("maxPlayers 또는 currentPlayers가 유효하지 않으면 공개 로비 목록에서 제외한다")
    void getPublicLobbies_excludesLobbyWithInvalidCapacityValues() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobbyWithCapacity("NULL-MAX", 1, null),
                lobbyWithCapacity("ZERO-MAX", 1, 0),
                lobbyWithCapacity("NULL-CURRENT", null, 6),
                lobbyWithCapacity("NEGATIVE-CURRENT", -1, 6),
                lobbyWithCapacity("VALID", 2, 6)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("VALID");
    }

    @Test
    @DisplayName("로비 상세 조회 응답의 참여자 목록과 최상위 방장 정보에 닉네임을 포함한다")
    void getLobbyDetail_includesPlayerNicknamesAndHostNickname() {
        // given
        String code = "ABC123";
        String hostIdentifier = "host-user-identifier";
        String participantIdentifier = "participant-user-identifier";

        JoinLobbyResponse lobbyInfo = lobbyDetailInfo(
                code,
                hostIdentifier,
                2,
                1L,
                "테스트 맵",
                "K-POP",
                10,
                30
        );

        stubLobbyDetailBase(code, lobbyInfo);

        when(lobbyRepository.getParticipantIdentifiers(code))
                .thenReturn(List.of(hostIdentifier, participantIdentifier));

        when(lobbyRepository.getReadyParticipantIdentifiers(code))
                .thenReturn(Set.of(participantIdentifier));

        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostIdentifier, participantIdentifier)))
                .thenReturn(Map.of(
                        hostIdentifier, "방장닉네임",
                        participantIdentifier, "참여자닉네임"
                ));

        // when
        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                registeredPrincipal(hostIdentifier)
        );

        // then
        assertThat(response.hostNickname()).isEqualTo("방장닉네임");

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::nickname)
                .containsExactly("방장닉네임", "참여자닉네임");

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::userIdentifier)
                .containsExactly(hostIdentifier, participantIdentifier);

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::host)
                .containsExactly(true, false);

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::ready)
                .containsExactly(false, true);
    }

    @Test
    @DisplayName("로비 상세 조회 응답에서 참가자 닉네임을 찾지 못하면 fallback 닉네임을 사용한다")
    void getLobbyDetail_usesFallbackNicknameWhenParticipantNicknameIsMissing() {
        // given
        String code = "ABC123";
        String hostIdentifier = "host-user-identifier";
        String participantIdentifier = "participant-user-identifier";

        JoinLobbyResponse lobbyInfo = lobbyDetailInfo(
                code,
                hostIdentifier,
                2
        );

        stubLobbyDetailBase(code, lobbyInfo);

        when(lobbyRepository.getParticipantIdentifiers(code))
                .thenReturn(List.of(hostIdentifier, participantIdentifier));

        when(lobbyRepository.getReadyParticipantIdentifiers(code))
                .thenReturn(Set.of());

        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostIdentifier, participantIdentifier)))
                .thenReturn(Map.of(
                        hostIdentifier, "방장닉네임"
                ));

        when(lobbyPlayerNicknameResolver.fallbackNickname(participantIdentifier))
                .thenReturn("Unknown-partic");

        // when
        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                registeredPrincipal(hostIdentifier)
        );

        // then
        assertThat(response.hostNickname()).isEqualTo("방장닉네임");

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::nickname)
                .containsExactly("방장닉네임", "Unknown-partic");
    }

    @Test
    @DisplayName("로비 상세 조회 응답에서 방장 닉네임을 찾지 못하면 fallback 닉네임을 hostNickname으로 내려준다")
    void getLobbyDetail_usesFallbackHostNicknameWhenHostNicknameIsMissing() {
        // given
        String code = "ABC123";
        String hostIdentifier = "missing-host-identifier";
        String participantIdentifier = "participant-user-identifier";

        JoinLobbyResponse lobbyInfo = lobbyDetailInfo(
                code,
                hostIdentifier,
                2
        );

        stubLobbyDetailBase(code, lobbyInfo);

        when(lobbyRepository.getParticipantIdentifiers(code))
                .thenReturn(List.of(hostIdentifier, participantIdentifier));

        when(lobbyRepository.getReadyParticipantIdentifiers(code))
                .thenReturn(Set.of(participantIdentifier));

        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostIdentifier, participantIdentifier)))
                .thenReturn(Map.of(
                        participantIdentifier, "참여자닉네임"
                ));

        when(lobbyPlayerNicknameResolver.fallbackNickname(hostIdentifier))
                .thenReturn("Unknown-host12");

        // when
        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                registeredPrincipal(hostIdentifier)
        );

        // then
        assertThat(response.hostNickname()).isEqualTo("Unknown-host12");

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::nickname)
                .containsExactly("Unknown-host12", "참여자닉네임");

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::host)
                .containsExactly(true, false);

        assertThat(response.players())
                .extracting(LobbyPlayerResponse::ready)
                .containsExactly(false, true);
    }

    @Test
    @DisplayName("로비 상세 조회 시 participants Set에 방장이 없으면 방장을 보정하고 hostNickname을 포함한다")
    void getLobbyDetail_addsMissingHostAndIncludesHostNickname() {
        // given
        String code = "ABC123";
        String hostIdentifier = "host-user-identifier";
        String participantIdentifier = "participant-user-identifier";

        JoinLobbyResponse lobbyInfo = lobbyDetailInfo(
                code,
                hostIdentifier,
                1
        );

        stubLobbyDetailBase(code, lobbyInfo);

        when(lobbyRepository.getParticipantIdentifiers(code))
                .thenReturn(List.of(participantIdentifier));

        when(lobbyRepository.getReadyParticipantIdentifiers(code))
                .thenReturn(Set.of(participantIdentifier));

        when(lobbyPlayerNicknameResolver.resolveNicknameMap(List.of(hostIdentifier, participantIdentifier)))
                .thenReturn(Map.of(
                        hostIdentifier, "방장닉네임",
                        participantIdentifier, "참가자닉네임"
                ));

        // when
        LobbyDetailResponse response = lobbyQueryService.getLobbyDetail(
                code,
                registeredPrincipal(hostIdentifier)
        );

        // then
        assertThat(response.hostNickname()).isEqualTo("방장닉네임");

        assertThat(response.players())
                .extracting(
                        LobbyPlayerResponse::userIdentifier,
                        LobbyPlayerResponse::nickname,
                        LobbyPlayerResponse::host,
                        LobbyPlayerResponse::ready
                )
                .containsExactly(
                        tuple(hostIdentifier, "방장닉네임", true, false),
                        tuple(participantIdentifier, "참가자닉네임", false, true)
                );

        verify(lobbyPlayerNicknameResolver)
                .resolveNicknameMap(List.of(hostIdentifier, participantIdentifier));
    }

    @Test
    @DisplayName("latest 정렬이고 필터가 없으면 공개 로비 최신순 ZSET 인덱스로 페이징 조회한다")
    void getPublicLobbyPage_usesLatestIndexWhenLatestSortWithoutFilters() {
        // given
        when(lobbyRepository.existsPublicLatestIndex()).thenReturn(true);

        when(lobbyRepository.getPublicLobbyCodesByLatestIndex(0L, 3)).thenReturn(List.of(
                "NEW",
                "MID",
                "OLD"
        ));

        when(lobbyRepository.getPublicLobbiesByCodes(List.of("NEW", "MID", "OLD"))).thenReturn(List.of(
                lobby("NEW", "최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("MID", "중간 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L),
                lobby("OLD", "오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 2);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("NEW", "MID");
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();

        verify(lobbyRepository).getPublicLobbyCodesByLatestIndex(0L, 3);
        verify(lobbyRepository).getPublicLobbiesByCodes(List.of("NEW", "MID", "OLD"));
        verify(lobbyRepository, never()).getPublicLobbies();
    }

    @Test
    @DisplayName("keyword 필터가 있으면 latest 정렬이어도 ZSET 인덱스를 사용하지 않고 전체 조회로 폴백한다")
    void getPublicLobbyPage_fallsBackToFullScanWhenKeywordExists() {
        // given
        when(lobbyRepository.existsPublicLatestIndex()).thenReturn(true);
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("MATCHED", "KPOP 랜덤 퀴즈", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("UNMATCHED", "JPOP 애니송", "J-POP", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of("랜덤", null, "latest", 0, 20);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("MATCHED");

        verify(lobbyRepository, never()).getPublicLobbyCodesByLatestIndex(anyLong(), anyInt());
        verify(lobbyRepository).getPublicLobbies();
    }

    @Test
    @DisplayName("mapCategory 필터가 있으면 latest 정렬이어도 ZSET 인덱스를 사용하지 않고 전체 조회로 폴백한다")
    void getPublicLobbyPage_fallsBackToFullScanWhenMapCategoryExists() {
        // given
        when(lobbyRepository.existsPublicLatestIndex()).thenReturn(true);
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("KPOP", "케이팝 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("JPOP", "제이팝 로비", "J-POP", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, "K-POP", "latest", 0, 20);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("KPOP");

        verify(lobbyRepository, never()).getPublicLobbyCodesByLatestIndex(anyLong(), anyInt());
        verify(lobbyRepository).getPublicLobbies();
    }

    @Test
    @DisplayName("latest ZSET 인덱스가 없으면 기존 공개 로비 전체 조회 방식으로 폴백한다")
    void getPublicLobbyPage_fallsBackToFullScanWhenLatestIndexDoesNotExist() {
        // given
        when(lobbyRepository.existsPublicLatestIndex()).thenReturn(false);
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("NEW", "최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("OLD", "오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 20);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("NEW", "OLD");

        verify(lobbyRepository, never()).getPublicLobbyCodesByLatestIndex(anyLong(), anyInt());
        verify(lobbyRepository).getPublicLobbies();
    }

    @Test
    @DisplayName("latest ZSET 조회 중 stale code가 있으면 다음 범위를 추가 조회해 page size를 채운다")
    void getPublicLobbyPageByLatestIndex_fetchesAdditionalRangeWhenStaleCodeExists() {
        // given
        when(lobbyRepository.existsPublicLatestIndex()).thenReturn(true);

        when(lobbyRepository.getPublicLobbyCodesByLatestIndex(0L, 3))
                .thenReturn(List.of("STALE", "NEW", "MID"));

        when(lobbyRepository.getPublicLobbiesByCodes(List.of("STALE", "NEW", "MID")))
                .thenReturn(List.of(
                        lobby("NEW", "최신 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                        lobby("MID", "중간 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 2000L)
                ));

        when(lobbyRepository.getPublicLobbyCodesByLatestIndex(3L, 1))
                .thenReturn(List.of("OLD"));

        when(lobbyRepository.getPublicLobbiesByCodes(List.of("OLD")))
                .thenReturn(List.of(
                        lobby("OLD", "오래된 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 1000L)
                ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 2);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("NEW", "MID");
        assertThat(result.hasNext()).isTrue();

        verify(lobbyRepository).getPublicLobbyCodesByLatestIndex(0L, 3);
        verify(lobbyRepository).getPublicLobbyCodesByLatestIndex(3L, 1);
    }

    @Test
    @DisplayName("latest ZSET 조회에서 유효 로비가 부족하면 가능한 만큼만 반환하고 hasNext=false를 반환한다")
    void getPublicLobbyPageByLatestIndex_returnsAvailableItemsWhenValidItemsAreInsufficient() {
        // given
        when(lobbyRepository.existsPublicLatestIndex()).thenReturn(true);

        when(lobbyRepository.getPublicLobbyCodesByLatestIndex(0L, 3))
                .thenReturn(List.of("STALE-1", "STALE-2", "ONLY"));

        when(lobbyRepository.getPublicLobbiesByCodes(List.of("STALE-1", "STALE-2", "ONLY")))
                .thenReturn(List.of(
                        lobby("ONLY", "유일한 유효 로비", "K-POP", 1, 6, LobbyStatus.WAITING, 1000L)
                ));

        when(lobbyRepository.getPublicLobbyCodesByLatestIndex(3L, 2))
                .thenReturn(List.of());

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest", 0, 2);

        // when
        var result = lobbyQueryService.getPublicLobbyPage(condition);

        // then
        assertThat(result.items())
                .extracting(LobbyListItemResponse::code)
                .containsExactly("ONLY");
        assertThat(result.hasNext()).isFalse();
    }

    private LobbyRedisDto lobby(
            String code,
            String title,
            String mapCategory,
            int currentPlayers,
            int maxPlayers,
            LobbyStatus status,
            Long createdAtEpochMillis
    ) {
        return lobby(
                code,
                title,
                mapCategory,
                currentPlayers,
                maxPlayers,
                status,
                createdAtEpochMillis,
                false
        );
    }

    private LobbyRedisDto lobby(
            String code,
            String title,
            String mapCategory,
            int currentPlayers,
            int maxPlayers,
            LobbyStatus status,
            Long createdAtEpochMillis,
            boolean isPrivate
    ) {
        return LobbyRedisDto.builder()
                .code(code)
                .hostId("host-user-id")
                .title(title)
                .mapId(1L)
                .mapTitle("테스트 맵")
                .mapCategory(mapCategory)
                .currentPlayers(currentPlayers)
                .maxPlayers(maxPlayers)
                .isPrivate(isPrivate)
                .status(status.name())
                .createdAtEpochMillis(createdAtEpochMillis)
                .build();
    }

    private LobbyRedisDto lobbyWithHost(
            String code,
            String hostId,
            Long createdAtEpochMillis
    ) {
        return LobbyRedisDto.builder()
                .code(code)
                .hostId(hostId)
                .title("로비 " + code)
                .mapId(1L)
                .mapTitle("테스트 맵")
                .mapCategory("K-POP")
                .currentPlayers(2)
                .maxPlayers(6)
                .isPrivate(false)
                .status(LobbyStatus.WAITING.name())
                .createdAtEpochMillis(createdAtEpochMillis)
                .build();
    }

    private LobbyRedisDto lobbyWithCapacity(
            String code,
            Integer currentPlayers,
            Integer maxPlayers
    ) {
        return LobbyRedisDto.builder()
                .code(code)
                .hostId("host-user-id")
                .title("테스트 로비")
                .mapId(1L)
                .mapTitle("테스트 맵")
                .mapCategory("K-POP")
                .currentPlayers(currentPlayers)
                .maxPlayers(maxPlayers)
                .isPrivate(false)
                .status(LobbyStatus.WAITING.name())
                .createdAtEpochMillis(1000L)
                .build();
    }

    private JoinLobbyResponse lobbyDetailInfo(
            String code,
            String hostIdentifier,
            int currentPlayers
    ) {
        return lobbyDetailInfo(
                code,
                hostIdentifier,
                currentPlayers,
                null,
                null,
                null,
                null,
                null
        );
    }

    private JoinLobbyResponse lobbyDetailInfo(
            String code,
            String hostIdentifier,
            int currentPlayers,
            Long mapId,
            String mapTitle,
            String mapCategory,
            Integer questionCount,
            Integer timeLimitSeconds
    ) {
        return new JoinLobbyResponse(
                code,
                "테스트 로비",
                hostIdentifier,
                8,
                currentPlayers,
                LobbyStatus.WAITING.name(),
                mapId,
                mapTitle,
                mapCategory,
                questionCount,
                timeLimitSeconds
        );
    }

    private void stubLobbyDetailBase(
            String code,
            JoinLobbyResponse lobbyInfo
    ) {
        when(lobbyRepository.findByInviteCode(code))
                .thenReturn(Optional.of(lobbyInfo));

        when(gameLobbyJpaRepository.findByInviteCode(code))
                .thenReturn(Optional.empty());

        when(lobbyCanStartPolicy.calculateCanStart(any(), any(), any()))
                .thenReturn(false);

        if (lobbyInfo.mapId() != null) {
            when(quizMapJpaRepository.findById(lobbyInfo.mapId()))
                    .thenReturn(Optional.of(quizMap(lobbyInfo.mapId(), 10)));
        }
    }

    private QuizMap quizMap(Long mapId, int numOfSong) {
        return QuizMap.builder()
                .id(mapId)
                .title("테스트 맵")
                .category(MapCategory.KPOP)
                .numOfSong(numOfSong)
                .totalPlayTime(300)
                .isPublic(true)
                .isDeleted(false)
                .build();
    }

    private CustomPrincipal registeredPrincipal(String userIdentifier) {
        return new CustomPrincipal(
                1L,
                userIdentifier,
                UserType.REGISTERED
        );
    }
}