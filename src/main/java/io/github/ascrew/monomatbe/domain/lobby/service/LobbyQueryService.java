/*
 * 로비 조회 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 공개 로비 목록 조회
 * - 공개 로비 목록 검색/필터/정렬/페이징 정책 적용
 * - Redis 정렬 인덱스 기반 공개 로비 목록 최적화 조회
 * - 로비 대기실 상세 조회
 * - 로비 상세 조회 접근 권한 검증
 * - 참여자 목록 조회 및 방장 누락 보정
 * - ready 상태 조회 및 플레이어 응답 조립
 * - 조회 시점의 canStart 계산 위임
 *
 * [주의]
 * canStart는 실제 게임 시작 가능 여부를 확정하는 값이 아니다.
 * FE의 시작 버튼 활성화를 위한 조회 시점 snapshot 값이다.
 * 실제 시작 가능 여부는 POST /api/lobbies/{code}/start에서 Redis Lua로 최종 검증한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyDetailResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPageRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPageResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyPlayerResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbySearchCondition;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbySortType;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyQueryService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "존재하지 않는 로비입니다.";
    private static final String ERROR_LOBBY_DETAIL_FORBIDDEN =
            "로비 참여자만 로비 상세 정보를 조회할 수 있습니다.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";

    private final LobbyRepository lobbyRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final LobbyCanStartPolicy lobbyCanStartPolicy;

    /**
     * 공개 로비 목록을 조회한다.
     *
     * [조회 기준]
     * Repository는 Redis의 lobby:public Set을 기준으로 공개 로비 원본 목록을 반환한다.
     * Service는 그 원본 목록에 실제 화면 노출 정책을 적용한다.
     *
     * [현재 적용 정책]
     * - WAITING 상태 로비는 입장 가능한 공개 로비로 목록에 노출한다.
     * - PLAYING 상태 로비는 진행 중 공개 로비로 목록에 노출한다.
     * - FINISHED 상태 로비는 목록에서 제외한다.
     * - keyword가 있으면 로비 제목에 keyword가 포함된 로비만 남긴다.
     * - mapCategory가 있으면 선택된 맵 카테고리가 일치하는 로비만 남긴다.
     *
     * [사용 상황]
     * - keyword/mapCategory 필터가 있는 경우
     * - Redis 정렬 인덱스가 아직 없는 경우
     * - 기존 테스트/호환 경로
     *
     * @param condition 공개 로비 목록 검색/필터/정렬 조건
     * @return 조건에 맞는 공개 로비 목록
     */
    @Transactional(readOnly = true)
    public List<LobbyRedisDto> getPublicLobbies(LobbySearchCondition condition) {
        List<LobbyRedisDto> publicLobbies = lobbyRepository.getPublicLobbies();

        return publicLobbies.stream()
                .filter(this::isPublicLobby)
                .filter(this::isVisibleLobby)
                .filter(this::hasValidCapacity)
                .filter(lobby -> matchesKeyword(lobby, condition))
                .filter(lobby -> matchesMapCategory(lobby, condition))
                .sorted(lobbyComparator(condition.sortType()))
                .toList();
    }

    /**
     * 공개 로비 목록을 페이징 응답으로 조회한다.
     *
     * [최적화 경로]
     * keyword/mapCategory 필터가 없고 요청 정렬 타입에 맞는 Redis ZSET 인덱스가 존재하면,
     * 전체 lobby:public을 조회하지 않고 정렬 인덱스에서 필요한 범위의 code만 조회한다.
     *
     * [폴백 경로]
     * keyword/mapCategory 필터가 있거나 정렬 인덱스가 없으면 기존 전체 조회 후
     * Java 필터/정렬/slice 방식으로 처리한다.
     *
     * @param condition 공개 로비 목록 검색/필터/정렬/페이징 조건
     * @return 페이징된 공개 로비 목록 응답
     */
    @Transactional(readOnly = true)
    public LobbyPageResponse<LobbyRedisDto> getPublicLobbyPage(LobbySearchCondition condition) {
        if (canUseSortIndex(condition)) {
            return getPublicLobbyPageBySortIndex(condition);
        }

        List<LobbyRedisDto> filteredLobbies = getPublicLobbies(condition);

        return toPageResponse(
                filteredLobbies,
                condition.pageRequest()
        );
    }

    /**
     * 정렬 인덱스를 사용할 수 있는 조건인지 확인한다.
     *
     * [사용 조건]
     * - keyword 필터 없음
     * - mapCategory 필터 없음
     * - 요청 sortType에 대응하는 Redis ZSET 인덱스 존재
     *
     * [필터 조건이 있을 때 인덱스를 사용하지 않는 이유]
     * ZSET에서 page 범위만 먼저 자른 뒤 필터링하면 전체 필터링 결과 기준의 page가 깨질 수 있다.
     * 예를 들어 ZSET 상위 20개에는 K-POP 로비가 없지만 21번째 이후에 K-POP 로비가 있을 수 있다.
     */
    private boolean canUseSortIndex(LobbySearchCondition condition) {
        return !condition.hasKeyword()
                && !condition.hasMapCategory()
                && existsSortIndex(condition.sortType());
    }

    /**
     * 정렬 타입에 대응하는 Redis ZSET 인덱스 존재 여부를 확인한다.
     *
     * [폴백 전략]
     * 인덱스가 없으면 기존 lobby:public 전체 조회 방식으로 폴백한다.
     * 배포 직후 기존 Redis 데이터에는 신규 인덱스가 없을 수 있기 때문이다.
     */
    private boolean existsSortIndex(LobbySortType sortType) {
        return switch (sortType) {
            case LATEST -> lobbyRepository.existsPublicLatestIndex();
            case MOST_PLAYERS -> lobbyRepository.existsPublicMostPlayersIndex();
            case MOST_AVAILABLE -> lobbyRepository.existsPublicMostAvailableIndex();
        };
    }

    /**
     * Redis ZSET 정렬 인덱스를 사용해 공개 로비 목록을 페이징 조회한다.
     *
     * [조회 전략]
     * - sortType에 맞는 ZSET에서 로비 코드를 조회한다.
     * - 조회된 코드에 대해서만 HGETALL/SCARD 또는 current_players 조회를 수행한다.
     * - stale code가 섞여 있을 수 있으므로 필요한 경우 다음 범위를 추가 조회한다.
     *
     * [정렬 보정]
     * Redis ZSET은 score 기준 정렬만 보장한다.
     * 같은 score의 로비끼리는 member 문자열 순서가 개입할 수 있으므로,
     * DTO 조회 후 Service comparator로 한 번 더 보정한다.
     *
     * [한계]
     * page offset 이전에 존재하는 stale code까지 완전히 보정하지는 않는다.
     * 다만 요청 중 만난 stale code는 Repository에서 제거하므로 반복 조회할수록 정합성이 회복된다.
     */
    private LobbyPageResponse<LobbyRedisDto> getPublicLobbyPageBySortIndex(
            LobbySearchCondition condition
    ) {
        LobbyPageRequest pageRequest = condition.pageRequest();

        int requestedSize = pageRequest.size();
        int targetItemCount = requestedSize + 1;

        List<LobbyRedisDto> collectedItems = new ArrayList<>();
        long scanOffset = pageRequest.offset();

        /*
         * stale code가 많은 상황에서도 무한히 Redis를 훑지 않도록 제한한다.
         * 정상 상황에서는 대부분 1회 조회로 끝난다.
         */
        int remainingScanAttempts = 3;

        while (collectedItems.size() < targetItemCount && remainingScanAttempts > 0) {
            int remainingItemCount = targetItemCount - collectedItems.size();

            List<String> indexedLobbyCodes = getLobbyCodesBySortIndex(
                    condition.sortType(),
                    scanOffset,
                    remainingItemCount
            );

            if (indexedLobbyCodes.isEmpty()) {
                break;
            }

            List<LobbyRedisDto> fetchedItems = lobbyRepository.getPublicLobbiesByCodes(indexedLobbyCodes).stream()
                    .filter(this::isPublicLobby)
                    .filter(this::isVisibleLobby)
                    .filter(this::hasValidCapacity)
                    .sorted(lobbyComparator(condition.sortType()))
                    .toList();

            collectedItems.addAll(fetchedItems);

            /*
             * 다음 조회는 이번에 ZSET에서 읽은 code 수만큼 뒤에서 시작한다.
             * fetchedItems 수가 아니라 indexedLobbyCodes 수를 기준으로 이동해야
             * stale code를 다시 읽지 않는다.
             */
            scanOffset += indexedLobbyCodes.size();
            remainingScanAttempts--;
        }

        boolean hasNext = collectedItems.size() > requestedSize;

        List<LobbyRedisDto> pageItems = hasNext
                ? collectedItems.subList(0, requestedSize)
                : collectedItems;

        return LobbyPageResponse.of(
                pageItems,
                pageRequest,
                hasNext
        );
    }

    /**
     * 정렬 타입에 맞는 Redis ZSET 인덱스에서 로비 코드를 조회한다.
     */
    private List<String> getLobbyCodesBySortIndex(
            LobbySortType sortType,
            long offset,
            int limit
    ) {
        return switch (sortType) {
            case LATEST -> lobbyRepository.getPublicLobbyCodesByLatestIndex(offset, limit);
            case MOST_PLAYERS -> lobbyRepository.getPublicLobbyCodesByMostPlayersIndex(offset, limit);
            case MOST_AVAILABLE -> lobbyRepository.getPublicLobbyCodesByMostAvailableIndex(offset, limit);
        };
    }

    /**
     * 이미 필터링/정렬된 로비 목록을 page/size 기준으로 잘라낸다.
     *
     * [hasNext 계산]
     * endExclusive가 전체 개수보다 작으면 다음 페이지가 존재한다.
     *
     * [범위 초과 page 처리]
     * 요청 page가 전체 목록 범위를 넘어가면 빈 items를 반환한다.
     * page 값 자체는 유효한 0 이상 값이므로 400으로 보지 않는다.
     */
    private LobbyPageResponse<LobbyRedisDto> toPageResponse(
            List<LobbyRedisDto> sortedLobbies,
            LobbyPageRequest pageRequest
    ) {
        long offset = pageRequest.offset();

        if (offset >= sortedLobbies.size()) {
            return LobbyPageResponse.of(
                    List.of(),
                    pageRequest,
                    false
            );
        }

        int startInclusive = Math.toIntExact(offset);
        int endExclusive = Math.min(
                startInclusive + pageRequest.size(),
                sortedLobbies.size()
        );

        List<LobbyRedisDto> pageItems = sortedLobbies.subList(
                startInclusive,
                endExclusive
        );

        boolean hasNext = endExclusive < sortedLobbies.size();

        return LobbyPageResponse.of(
                pageItems,
                pageRequest,
                hasNext
        );
    }

    /**
     * 공개 로비 목록에 노출 가능한 공개 로비인지 확인한다.
     *
     * [정책]
     * - isPrivate == false인 로비만 공개 목록에 노출한다.
     * - isPrivate가 null이면 Redis 손상 데이터로 보고 제외한다.
     *
     * [필요 이유]
     * 정상 생성 경로에서는 create_lobby.lua가 공개 로비만 lobby:public Set에 추가한다.
     * 하지만 운영 중 Redis 수동 조작, 과거 데이터, 복구 작업 등으로
     * lobby:public Set에 비공개 로비 코드가 섞일 수 있다.
     *
     * 공개 로비 목록은 사용자에게 직접 노출되는 API이므로,
     * Service 계층에서도 최종 방어 필터를 둔다.
     */
    private boolean isPublicLobby(LobbyRedisDto lobby) {
        if (lobby == null || lobby.getIsPrivate() == null) {
            return false;
        }

        return !lobby.getIsPrivate();
    }

    /**
     * 공개 로비 목록에 노출 가능한 상태인지 확인한다.
     *
     * [노출 상태]
     * - WAITING: 입장 가능한 공개 로비
     * - PLAYING: 진행 중 공개 로비. 목록에는 노출하지만 입장은 허용하지 않음
     *
     * [제외 상태]
     * - FINISHED: 종료된 로비이므로 목록에서 제외
     * - null/blank/unknown: Redis 손상 데이터로 보고 제외
     */
    private boolean isVisibleLobby(LobbyRedisDto lobby) {
        if (lobby == null || lobby.getStatus() == null || lobby.getStatus().isBlank()) {
            return false;
        }

        String status = lobby.getStatus();

        return LobbyStatus.WAITING.name().equals(status)
                || LobbyStatus.PLAYING.name().equals(status);
    }

    /**
     * 로비 목록에 노출 가능한 인원/정원 값인지 확인한다.
     *
     * [정책]
     * - maxPlayers가 null 또는 0 이하이면 제외한다.
     * - currentPlayers가 null 또는 음수이면 제외한다.
     * - currentPlayers가 maxPlayers보다 크면 제외한다.
     *
     * [필요 이유]
     * 공개 로비 목록은 사용자가 입장 가능한 로비를 탐색하는 화면이다.
     * Redis 데이터 손상으로 maxPlayers가 누락되거나 currentPlayers가 maxPlayers를 초과한 로비가
     * 목록에 노출되면 FE에서는 입장 가능한 방처럼 보이지만 실제 입장 단계에서 실패할 수 있다.
     *
     * 따라서 정렬 단계에서 숫자를 0으로 보정하는 것과 별개로,
     * 목록 노출 전에 capacity 값 자체가 유효한 로비만 통과시킨다.
     */
    private boolean hasValidCapacity(LobbyRedisDto lobby) {
        if (lobby == null) {
            return false;
        }

        Integer maxPlayers = lobby.getMaxPlayers();
        Integer currentPlayers = lobby.getCurrentPlayers();

        if (maxPlayers == null || maxPlayers <= 0) {
            log.warn(
                    "공개 로비 목록 제외 - maxPlayers 값이 유효하지 않음. lobbyCode: {}, maxPlayers: {}",
                    lobby.getCode(),
                    maxPlayers
            );
            return false;
        }

        if (currentPlayers == null || currentPlayers < 0) {
            log.warn(
                    "공개 로비 목록 제외 - currentPlayers 값이 유효하지 않음. lobbyCode: {}, currentPlayers: {}",
                    lobby.getCode(),
                    currentPlayers
            );
            return false;
        }

        if (currentPlayers > maxPlayers) {
            log.warn(
                    "공개 로비 목록 제외 - currentPlayers가 maxPlayers를 초과. lobbyCode: {}, currentPlayers: {}, maxPlayers: {}",
                    lobby.getCode(),
                    currentPlayers,
                    maxPlayers
            );
            return false;
        }

        return true;
    }

    /**
     * 제목 검색 조건에 맞는지 확인한다.
     *
     * [검색 정책]
     * - keyword가 없으면 모든 로비를 통과시킨다.
     * - keyword가 있으면 title에 keyword가 포함된 경우만 통과시킨다.
     * - 대소문자 차이는 무시한다.
     *
     * [정규화 책임]
     * LobbySearchCondition은 keyword를 생성 시점에 trim + lower-case로 정규화한다.
     * 따라서 여기서는 로비 title만 lower-case로 변환하여 비교한다.
     *
     * [null 처리]
     * Redis에 title 필드가 누락된 손상 데이터는 검색 조건이 있을 때 매칭하지 않는다.
     * 검색 조건이 없을 때는 상태/카테고리 등 다른 조건만 만족하면 통과할 수 있다.
     */
    private boolean matchesKeyword(
            LobbyRedisDto lobby,
            LobbySearchCondition condition
    ) {
        if (!condition.hasKeyword()) {
            return true;
        }

        String title = lobby.getTitle();
        if (title == null || title.isBlank()) {
            return false;
        }

        return title.toLowerCase(Locale.ROOT)
                .contains(condition.keyword());
    }

    /**
     * 맵 카테고리 필터 조건에 맞는지 확인한다.
     *
     * [필터 정책]
     * - mapCategory 조건이 없으면 모든 로비를 통과시킨다.
     * - mapCategory 조건이 있으면 선택된 맵 카테고리가 일치하는 로비만 통과시킨다.
     *
     * [맵 미선택 로비 처리]
     * 카테고리 필터가 적용된 경우, 맵이 선택되지 않은 로비는 제외된다.
     *
     * [전제]
     * LobbyRedisQueryRepository는 Redis raw mapCategory를 FE 표시값으로 정규화하여 DTO에 담는다.
     * 따라서 Service에서는 이미 정규화된 표시값끼리 직접 비교한다.
     */
    private boolean matchesMapCategory(
            LobbyRedisDto lobby,
            LobbySearchCondition condition
    ) {
        if (!condition.hasMapCategory()) {
            return true;
        }

        String lobbyMapCategory = lobby.getMapCategory();
        if (lobbyMapCategory == null || lobbyMapCategory.isBlank()) {
            return false;
        }

        return condition.mapCategory().value().equals(lobbyMapCategory);
    }

    /**
     * 공개 로비 목록 정렬 기준을 Comparator로 변환한다.
     *
     * [정렬 정책]
     * - LATEST
     *   createdAtEpochMillis 내림차순.
     *   생성 시각 필드가 없는 기존 Redis 데이터는 0으로 취급하여 뒤로 보낸다.
     *
     * - MOST_PLAYERS
     *   currentPlayers 내림차순.
     *   동률이면 최신순으로 정렬한다.
     *
     * - MOST_AVAILABLE
     *   남은 자리 수(maxPlayers - currentPlayers) 내림차순.
     *   동률이면 최신순으로 정렬한다.
     *
     * [null 처리]
     * Redis 데이터는 운영 중 TTL 만료, 과거 버전 데이터, 수동 수정 등으로 일부 숫자 필드가 누락될 수 있다.
     * 목록 정렬은 사용자 편의 기능이므로, 숫자 필드 누락으로 전체 요청을 실패시키지 않고 안전한 기본값으로 보정한다.
     */
    private Comparator<LobbyRedisDto> lobbyComparator(LobbySortType sortType) {
        return switch (sortType) {
            case LATEST -> latestComparator();
            case MOST_PLAYERS -> mostPlayersComparator();
            case MOST_AVAILABLE -> mostAvailableComparator();
        };
    }

    /**
     * 최신순 정렬 Comparator
     *
     * createdAtEpochMillis 값이 클수록 최근 생성된 로비다.
     * null은 0으로 취급하여 최신순 정렬에서 뒤로 보낸다.
     */
    private Comparator<LobbyRedisDto> latestComparator() {
        return Comparator.comparingLong(this::createdAtEpochMillisOrDefault)
                .reversed();
    }

    /**
     * 현재 인원 많은 순 정렬 Comparator
     *
     * currentPlayers가 큰 로비를 먼저 보여준다.
     * 같은 인원 수라면 사용자가 더 최근에 생성된 로비를 먼저 볼 수 있도록 최신순을 2차 정렬로 사용한다.
     */
    private Comparator<LobbyRedisDto> mostPlayersComparator() {
        return Comparator.comparingInt(this::currentPlayersOrDefault)
                .reversed()
                .thenComparing(latestComparator());
    }

    /**
     * 빈자리 많은 순 정렬 Comparator
     *
     * maxPlayers - currentPlayers 값이 큰 로비를 먼저 보여준다.
     * 같은 빈자리 수라면 최신순을 2차 정렬로 사용한다.
     */
    private Comparator<LobbyRedisDto> mostAvailableComparator() {
        return Comparator.comparingInt(this::availableSeatsOrDefault)
                .reversed()
                .thenComparing(latestComparator());
    }

    /**
     * 로비 생성 시각을 정렬 가능한 long 값으로 변환한다.
     *
     * 기존 Redis 데이터에는 created_at_epoch_millis가 없을 수 있으므로 null이면 0으로 보정한다.
     */
    private long createdAtEpochMillisOrDefault(LobbyRedisDto lobby) {
        return lobby.getCreatedAtEpochMillis() != null
                ? lobby.getCreatedAtEpochMillis()
                : 0L;
    }

    /**
     * 현재 인원 값을 정렬 가능한 int 값으로 변환한다.
     *
     * Redis 조회 중 participants Set 크기 조회가 null이거나,
     * 과거 데이터에서 currentPlayers가 누락된 경우 0으로 보정한다.
     */
    private int currentPlayersOrDefault(LobbyRedisDto lobby) {
        return lobby.getCurrentPlayers() != null
                ? lobby.getCurrentPlayers()
                : 0;
    }

    /**
     * 최대 인원 값을 정렬 가능한 int 값으로 변환한다.
     *
     * maxPlayers는 정상 로비라면 항상 존재해야 하지만,
     * Redis Hash 손상 가능성을 고려해 null이면 0으로 보정한다.
     */
    private int maxPlayersOrDefault(LobbyRedisDto lobby) {
        return lobby.getMaxPlayers() != null
                ? lobby.getMaxPlayers()
                : 0;
    }

    /**
     * 남은 자리 수를 계산한다.
     *
     * [보정 정책]
     * Redis 데이터가 손상되어 currentPlayers가 maxPlayers보다 큰 경우에도
     * 음수 빈자리가 정렬에 영향을 주지 않도록 0으로 보정한다.
     */
    private int availableSeatsOrDefault(LobbyRedisDto lobby) {
        return Math.max(
                0,
                maxPlayersOrDefault(lobby) - currentPlayersOrDefault(lobby)
        );
    }

    /**
     * 로비 대기실 상세 정보를 조회한다.
     *
     * [조회 대상]
     * - 로비 기본 정보
     * - DB에 저장된 룰 정보(roundCount, timeLimitSeconds)
     * - Redis 참여자 목록
     * - Redis ready 상태
     * - 현재 시작 가능 여부(canStart)
     *
     * [접근 정책]
     * 로비 상세 정보에는 참여자 ready 상태가 포함되므로,
     * 로비 참여자 또는 방장만 조회할 수 있도록 제한한다.
     *
     * [중요]
     * canStart는 조회 시점 snapshot 값이다.
     * 실제 게임 시작 가능 여부는 LobbyStartService에서 최종 검증한다.
     *
     * @param code      로비 초대 코드
     * @param principal JWT에서 추출한 인증 주체
     * @return 로비 상세 응답
     */
    @Transactional(readOnly = true)
    public LobbyDetailResponse getLobbyDetail(String code, CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            log.warn("로비 상세 조회 거부 - principal 또는 userId가 null. code: {}", code);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        JoinLobbyResponse lobbyInfo = lobbyRepository.findByInviteCode(code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        String userIdentifier = principal.userIdentifier();

        if (!canAccessLobbyDetail(code, lobbyInfo, userIdentifier)) {
            log.warn(
                    "로비 상세 조회 거부 - 참여자가 아님. code: {}, userIdentifier: {}, hostId: {}",
                    code,
                    userIdentifier,
                    lobbyInfo.hostId()
            );
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_LOBBY_DETAIL_FORBIDDEN);
        }

        List<String> participantIdentifiers = includeHostIfMissing(
                lobbyRepository.getParticipantIdentifiers(code),
                lobbyInfo.hostId(),
                code
        );

        Set<String> readyParticipantIdentifiers = lobbyRepository.getReadyParticipantIdentifiers(code);

        List<LobbyPlayerResponse> players = participantIdentifiers.stream()
                .map(participantIdentifier -> toLobbyPlayerResponse(
                        participantIdentifier,
                        lobbyInfo.hostId(),
                        readyParticipantIdentifiers
                ))
                .toList();

        GameLobby gameLobby = gameLobbyJpaRepository.findByInviteCode(code)
                .orElse(null);

        boolean canStart = lobbyCanStartPolicy.calculateCanStart(
                lobbyInfo,
                players,
                gameLobby
        );

        return LobbyDetailResponse.builder()
                .inviteCode(lobbyInfo.inviteCode())
                .title(lobbyInfo.title())
                .hostId(lobbyInfo.hostId())
                .maxPlayers(lobbyInfo.maxPlayers())
                .currentPlayers(Math.max(lobbyInfo.currentPlayers(), players.size()))
                .status(lobbyInfo.status())
                .mapId(lobbyInfo.mapId())
                .mapTitle(lobbyInfo.mapTitle())
                .mapCategory(lobbyInfo.mapCategory())
                .roundCount(gameLobby != null ? gameLobby.getRoundCount() : null)
                .timeLimitSeconds(gameLobby != null ? gameLobby.getTimeLimitSeconds() : null)
                .players(players)
                .canStart(canStart)
                .build();
    }

    /**
     * 요청자가 로비 방장인지 확인한다.
     *
     * Redis 로비 정보의 hostId는 userIdentifier 기준으로 저장되므로,
     * JWT principal의 userIdentifier와 직접 비교한다.
     */
    private boolean isLobbyHost(JoinLobbyResponse lobbyInfo, String userIdentifier) {
        return lobbyInfo.hostId() != null && lobbyInfo.hostId().equals(userIdentifier);
    }

    /**
     * 로비 상세 조회 권한을 확인한다.
     *
     * [정책]
     * - 방장은 participants Set 누락 여부와 관계없이 조회할 수 있다.
     * - 일반 유저는 WebSocket 구독으로 participants Set에 등록된 이후 조회할 수 있다.
     */
    private boolean canAccessLobbyDetail(
            String code,
            JoinLobbyResponse lobbyInfo,
            String userIdentifier
    ) {
        if (isLobbyHost(lobbyInfo, userIdentifier)) {
            return true;
        }

        return lobbyRepository.isParticipant(code, userIdentifier);
    }

    /**
     * 참여자 식별자를 로비 상세 응답용 플레이어 DTO로 변환한다.
     *
     * [방장 ready 정책]
     * 방장은 ready 대상에서 제외하므로 ready=false로 내려간다.
     * FE는 host=true 여부를 기준으로 ready 버튼을 숨김 처리한다.
     */
    private LobbyPlayerResponse toLobbyPlayerResponse(
            String participantIdentifier,
            String hostId,
            Set<String> readyParticipantIdentifiers
    ) {
        boolean host = hostId != null && hostId.equals(participantIdentifier);
        boolean ready = !host && readyParticipantIdentifiers.contains(participantIdentifier);

        return new LobbyPlayerResponse(
                participantIdentifier,
                host,
                ready
        );
    }

    /**
     * 로비 상세 응답에서 방장 정보가 누락되지 않도록 보정한다.
     *
     * [필요 이유]
     * 정상 흐름에서는 로비 생성 시 방장이 participants Set에 포함되어야 한다.
     * 다만 Redis 데이터 손상, 과거 데이터, WebSocket 입장 상태 불일치가 있으면 participants Set에서 방장이 누락될 수 있다.
     *
     * FE 대기실 UI는 players 목록의 host=true 값을 기준으로 방장 표시/시작 버튼/ready 버튼을 분기하므로,
     * 상세 응답에서는 hostId가 존재하면 players 목록에 방장을 보장한다.
     */
    private List<String> includeHostIfMissing(
            List<String> participantIdentifiers,
            String hostId,
            String code
    ) {
        if (hostId == null || hostId.isBlank() || participantIdentifiers.contains(hostId)) {
            return participantIdentifiers;
        }

        List<String> result = new ArrayList<>(participantIdentifiers);
        result.add(0, hostId);

        log.warn(
                "{} 로비 상세 응답 보정 - participants Set에 방장이 없어 응답 목록에 추가. "
                        + "code: {}, hostId: {}",
                LOG_ALERT_REQUIRED,
                code,
                hostId
        );

        return result;
    }
}