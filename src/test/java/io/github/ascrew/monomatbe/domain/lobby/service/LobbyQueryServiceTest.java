package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbySearchCondition;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LobbyQueryService의 공개 로비 목록 조회 정책을 검증한다.
 *
 * [테스트 범위]
 * - Redis 조회 자체는 LobbyRepository의 책임이므로 mock 처리한다.
 * - 이 테스트는 Service 계층의 목록 노출 정책만 검증한다.
 *
 * [검증 정책]
 * - PLAYING / FINISHED 로비는 기본 목록에서 제외한다.
 * - 제목 검색은 대소문자를 무시하고 contains 기준으로 동작한다.
 * - 검색어는 LobbySearchCondition 생성 시점에 lower-case로 정규화된다.
 * - 카테고리 필터는 FE 표시값(K-POP, J-POP, POP)을 기준으로 동작한다.
 * - Redis에 잘못된 mapCategory 값이 있어도 전체 API가 실패하지 않고 해당 로비만 제외한다.
 * - 정렬은 최신순, 인원 많은 순, 빈자리 많은 순을 지원한다.
 */
class LobbyQueryServiceTest {

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final GameLobbyJpaRepository gameLobbyJpaRepository = mock(GameLobbyJpaRepository.class);
    private final LobbyCanStartPolicy lobbyCanStartPolicy = mock(LobbyCanStartPolicy.class);

    private final LobbyQueryService lobbyQueryService = new LobbyQueryService(
            lobbyRepository,
            gameLobbyJpaRepository,
            lobbyCanStartPolicy
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

        /*
         * LobbySearchCondition 생성 시점에 keyword가 lower-case로 정규화된다.
         * Service는 로비 title만 lower-case로 변환하여 비교하므로,
         * 요청 keyword의 대소문자 차이는 결과에 영향을 주지 않아야 한다.
         */
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
                lobby("LOBBY-2", "JPOP Anime Quiz", "J-POP", 2, 6, LobbyStatus.WAITING, 2000L)
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
    @DisplayName("카테고리 필터 적용 시 Redis에 잘못된 mapCategory가 있으면 해당 로비만 제외한다")
    void getPublicLobbies_excludesLobbyWithInvalidRedisMapCategory() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("VALID", "정상 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("INVALID", "손상 로비", "UNKNOWN", 2, 6, LobbyStatus.WAITING, 2000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, "K-POP", "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("VALID");
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
    @DisplayName("기본 조회에서도 Redis에 잘못된 mapCategory가 있으면 해당 로비를 제외한다")
    void getPublicLobbies_excludesLobbyWithInvalidRedisMapCategoryWithoutCategoryFilter() {
        // given
        when(lobbyRepository.getPublicLobbies()).thenReturn(List.of(
                lobby("VALID", "정상 카테고리 로비", "K-POP", 2, 6, LobbyStatus.WAITING, 3000L),
                lobby("NO-MAP", "맵 미선택 로비", null, 2, 6, LobbyStatus.WAITING, 2000L),
                lobby("INVALID", "손상 카테고리 로비", "UNKNOWN", 2, 6, LobbyStatus.WAITING, 1000L)
        ));

        LobbySearchCondition condition = LobbySearchCondition.of(null, null, "latest");

        // when
        List<LobbyRedisDto> result = lobbyQueryService.getPublicLobbies(condition);

        // then
        assertThat(result)
                .extracting(LobbyRedisDto::getCode)
                .containsExactly("VALID", "NO-MAP");
    }

    /**
     * 테스트용 로비 DTO를 생성한다.
     *
     * [의도]
     * 각 테스트에서 필요한 값만 명확히 드러내기 위해 fixture 생성 메서드를 둔다.
     * 실제 Redis 조회 로직은 테스트 대상이 아니므로,
     * LobbyRedisDto builder를 사용해 Service 정책 검증에 필요한 필드만 채운다.
     */
    private LobbyRedisDto lobby(
            String code,
            String title,
            String mapCategory,
            int currentPlayers,
            int maxPlayers,
            LobbyStatus status,
            Long createdAtEpochMillis
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
                .isPrivate(false)
                .status(status.name())
                .createdAtEpochMillis(createdAtEpochMillis)
                .build();
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
}