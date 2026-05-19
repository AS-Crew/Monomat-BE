package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 로비 Redis 조회 전용 Repository
 *
 * [담당 범위]
 * - 로비 존재 여부 조회
 * - 로비 참여자 여부 조회
 * - 입장 순서 기반 참여자 목록 조회
 * - ready 참여자 목록 조회
 * - 공개 로비 목록 조회
 * - Redis ZSET 정렬 인덱스 기반 로비 코드 조회
 * - 초대 코드 기반 로비 기본 정보 조회
 * - 현재 참여 인원 수 조회
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRedisQueryRepository {

    /**
     * 로비가 존재하지 않거나 TTL이 만료된 경우 반환할 빈 Optional.
     * 매번 새 객체를 생성하지 않기 위해 상수로 관리한다.
     */
    private static final Optional<JoinLobbyResponse> EMPTY_LOBBY = Optional.empty();

    private static final String ERROR_INVALID_LOBBY_DATA =
            "로비 정보가 유효하지 않습니다.";

    private final StringRedisTemplate redisTemplate;

    /**
     * 해당 코드의 로비가 Redis에 존재하는지 확인한다.
     *
     * @param code 로비 초대 코드
     * @return 로비 Redis Hash 존재 여부
     */
    public boolean existsByCode(String code) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.lobbyKey(code)));
    }

    /**
     * 해당 유저가 로비 참여자인지 확인한다.
     *
     * @param code 로비 초대 코드
     * @param userId 사용자 식별자
     * @return participants Set 포함 여부
     */
    public boolean isParticipant(String code, String userId) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForSet().isMember(RedisKeys.lobbyParticipantsKey(code), userId)
        );
    }

    /**
     * 로비 참여자 목록을 입장 순서 기준으로 조회한다.
     *
     * [조회 전략]
     * - lobby:{code}:order List를 우선 사용하여 FE 표시 순서를 안정적으로 유지한다.
     * - participants Set을 함께 조회하여 이미 퇴장했지만 order에 남은 값은 제거한다.
     * - order List에는 없지만 participants Set에는 존재하는 비정상 데이터는 응답 누락 방지를 위해 뒤에 보정한다.
     *
     * @param code 로비 초대 코드
     * @return 현재 로비에 참여 중인 userIdentifier 목록
     */
    public List<String> getParticipantIdentifiers(String code) {
        Set<String> participantSet = redisTemplate.opsForSet()
                .members(RedisKeys.lobbyParticipantsKey(code));

        if (participantSet == null || participantSet.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> orderedParticipants = redisTemplate.opsForList()
                .range(RedisKeys.lobbyOrderKey(code), 0, -1);

        if (orderedParticipants == null || orderedParticipants.isEmpty()) {
            return new ArrayList<>(participantSet);
        }

        List<String> result = new ArrayList<>();

        for (String userIdentifier : orderedParticipants) {
            if (participantSet.contains(userIdentifier)) {
                result.add(userIdentifier);
            }
        }

        /*
         * Redis 장애, 과거 데이터, Lua 계약 변경 상황에서도 상세 응답이 누락되지 않도록
         * participants Set에만 존재하는 사용자를 뒤에 추가한다.
         */
        for (String userIdentifier : participantSet) {
            if (!result.contains(userIdentifier)) {
                result.add(userIdentifier);
            }
        }

        return result;
    }

    /**
     * ready 상태인 참여자 식별자 목록을 조회한다.
     *
     * [반환 정책]
     * Redis Set 조회 결과가 null이면 빈 Set으로 반환하여
     * 서비스 레이어에서 null 방어 로직을 반복하지 않도록 한다.
     *
     * @param code 로비 초대 코드
     * @return ready 상태인 userIdentifier Set
     */
    public Set<String> getReadyParticipantIdentifiers(String code) {
        Set<String> readyParticipants = redisTemplate.opsForSet()
                .members(RedisKeys.lobbyReadyKey(code));

        if (readyParticipants == null || readyParticipants.isEmpty()) {
            return Set.of();
        }

        return readyParticipants;
    }

    /**
     * Redis에서 공개 로비 목록을 조회한다.
     *
     * [조회 기준]
     * lobby:public Set에 들어 있는 초대 코드를 기준으로 lobby:{code} Hash와
     * lobby:{code}:participants Set 크기를 조회한다.
     *
     * [성능]
     * 공개 로비 수가 N개일 때 개별 Redis 호출을 수행하면
     * SMEMBERS 1회 + HGETALL N회 + SCARD N회로 총 1 + 2N번의 round-trip이 발생한다.
     *
     * 이 메서드는 HGETALL과 SCARD를 executePipelined()로 묶어
     * Redis 명령 수는 유지하되 네트워크 round-trip을 줄인다.
     *
     * [주의]
     * lobby:public에는 남아 있지만 lobby:{code} Hash가 TTL 만료 등으로 사라진 경우는
     * getPublicLobbiesByCodes()에서 응답 제외 및 stale index 정리를 수행한다.
     *
     * @return 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        Set<String> publicLobbyCodes =
                redisTemplate.opsForSet().members(RedisKeys.LOBBY_PUBLIC);

        if (publicLobbyCodes == null || publicLobbyCodes.isEmpty()) {
            return new ArrayList<>();
        }

        /*
         * Set은 순서 보장을 전제로 쓰면 안 된다.
         * 기존 전체 조회 경로는 Service 계층에서 정렬하므로 여기서는 순서가 중요하지 않다.
         */
        return getPublicLobbiesByCodes(new ArrayList<>(publicLobbyCodes));
    }

    /**
     * 공개 로비 stale index 정리용 후보 코드를 조회한다.
     *
     * [조회 기준]
     * lobby:public Set을 기준으로 일부 code만 가져온다.
     *
     * [주의]
     * Set은 순서를 보장하지 않으므로 이 메서드는 정렬 용도가 아니다.
     * 배치 스캐너가 한 번에 처리할 후보 수를 제한하기 위한 용도다.
     *
     * @param limit 조회할 최대 code 수
     * @return 정리 후보 code 목록
     */
    public List<String> getPublicLobbyCodesForCleanup(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        Set<String> codes = redisTemplate.opsForSet()
                .distinctRandomMembers(RedisKeys.LOBBY_PUBLIC, limit);

        if (codes == null || codes.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(codes);
    }

    /**
     * 공개 로비 최신순 ZSET 인덱스 존재 여부를 확인한다.
     *
     * @return lobby:public:latest 존재 여부
     */
    public boolean existsPublicLatestIndex() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.LOBBY_PUBLIC_LATEST));
    }

    /**
     * 공개 로비 현재 인원 많은 순 ZSET 인덱스 존재 여부를 확인한다.
     *
     * @return lobby:public:most_players 존재 여부
     */
    public boolean existsPublicMostPlayersIndex() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS));
    }

    /**
     * 공개 로비 빈자리 많은 순 ZSET 인덱스 존재 여부를 확인한다.
     *
     * @return lobby:public:most_available 존재 여부
     */
    public boolean existsPublicMostAvailableIndex() {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE));
    }

    /**
     * 공개 로비 최신순 ZSET 인덱스에서 로비 코드를 조회한다.
     *
     * score가 높은 로비가 먼저 와야 하므로 reverseRange를 사용한다.
     *
     * @param offset 0-based 조회 시작 offset
     * @param limit 조회 개수
     * @return 최신순 로비 코드 목록
     */
    public List<String> getPublicLobbyCodesByLatestIndex(long offset, int limit) {
        return getPublicLobbyCodesFromZSet(
                RedisKeys.LOBBY_PUBLIC_LATEST,
                offset,
                limit,
                true
        );
    }

    /**
     * 공개 로비 현재 인원 많은 순 ZSET 인덱스에서 로비 코드를 조회한다.
     *
     * score가 높은 로비가 먼저 와야 하므로 reverseRange를 사용한다.
     *
     * @param offset 0-based 조회 시작 offset
     * @param limit 조회 개수
     * @return 현재 인원 많은 순 로비 코드 목록
     */
    public List<String> getPublicLobbyCodesByMostPlayersIndex(long offset, int limit) {
        return getPublicLobbyCodesFromZSet(
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                offset,
                limit,
                true
        );
    }

    /**
     * 공개 로비 빈자리 많은 순 ZSET 인덱스에서 로비 코드를 조회한다.
     *
     * score가 높은 로비가 먼저 와야 하므로 reverseRange를 사용한다.
     *
     * @param offset 0-based 조회 시작 offset
     * @param limit 조회 개수
     * @return 빈자리 많은 순 로비 코드 목록
     */
    public List<String> getPublicLobbyCodesByMostAvailableIndex(long offset, int limit) {
        return getPublicLobbyCodesFromZSet(
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE,
                offset,
                limit,
                true
        );
    }

    /**
     * Redis ZSET에서 로비 코드를 조회한다.
     *
     * @param zsetKey ZSET key
     * @param offset 0-based 조회 시작 offset
     * @param limit 조회 개수
     * @param reverse true면 score 내림차순, false면 score 오름차순
     * @return 조회된 로비 코드 목록
     */
    private List<String> getPublicLobbyCodesFromZSet(
            String zsetKey,
            long offset,
            int limit,
            boolean reverse
    ) {
        if (limit <= 0) {
            return List.of();
        }

        Set<String> codes = reverse
                ? redisTemplate.opsForZSet().reverseRange(zsetKey, offset, offset + limit - 1L)
                : redisTemplate.opsForZSet().range(zsetKey, offset, offset + limit - 1L);

        if (codes == null || codes.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(codes);
    }

    /**
     * 주어진 로비 코드 목록에 대해서만 Redis Hash와 현재 참여자 수를 조회한다.
     *
     * [사용 목적]
     * - 기존 lobby:public Set 전체 조회
     * - 신규 lobby:public:latest ZSET 범위 조회
     * - 신규 lobby:public:most_players ZSET 범위 조회
     * - 신규 lobby:public:most_available ZSET 범위 조회
     *
     * 두 경로 모두 code 목록만 다르고 DTO 조립 방식은 동일하므로,
     * 이 메서드로 HGETALL + SCARD pipeline 로직을 공통화한다.
     *
     * [정합성 방어]
     * - code는 인덱스에 있지만 lobby:{code} Hash가 없으면 제외하고 공개 인덱스에서 제거한다.
     * - mapCategory 필드가 손상된 로비는 제외한다.
     *
     * @param lobbyCodes 조회할 로비 코드 목록
     * @return 조회 가능한 로비 DTO 목록
     */
    public List<LobbyRedisDto> getPublicLobbiesByCodes(List<String> lobbyCodes) {
        if (lobbyCodes == null || lobbyCodes.isEmpty()) {
            return new ArrayList<>();
        }

        List<Object> pipelineResults = redisTemplate.executePipelined((RedisConnection connection) -> {
            for (String code : lobbyCodes) {
                connection.hashCommands().hGetAll(rawKey(RedisKeys.lobbyKey(code)));
                connection.setCommands().sCard(rawKey(RedisKeys.lobbyParticipantsKey(code)));
            }

            return null;
        });

        List<LobbyRedisDto> result = new ArrayList<>();

        for (int i = 0; i < lobbyCodes.size(); i++) {
            String code = lobbyCodes.get(i);

            int hashResultIndex = i * 2;
            int countResultIndex = hashResultIndex + 1;

            Map<Object, Object> data = extractHashResult(
                    pipelineResults,
                    hashResultIndex,
                    code
            );

            if (data.isEmpty()) {
                /*
                 * 인덱스에는 남아 있지만 lobby:{code} Hash가 없는 상태다.
                 *
                 * 조회 경로에서는 stale code를 응답에서 제외만 한다.
                 * 인덱스 삭제는 별도 스케줄러가 수행한다.
                 *
                 * 이유:
                 * - 목록 조회 요청이 Redis 인덱스를 직접 변경하면 읽기 경로와 정리 책임이 섞인다.
                 * - 동시 요청 중 같은 code를 참조하는 다른 요청과 타이밍이 겹치면 일시적인 목록 누락/순서 흔들림을 유발할 수 있다.
                 */
                log.warn(
                        "공개 로비 stale index 감지 - 배치 스캐너에서 정리 예정. lobbyCode: {}",
                        code
                );
                continue;
            }

            Integer currentPlayers = resolveCurrentPlayers(
                    data,
                    pipelineResults,
                    countResultIndex,
                    code
            );

            String displayMapCategory = toDisplayMapCategoryOrNull(
                    code,
                    (String) data.get(RedisKeys.FIELD_MAP_CATEGORY)
            );

            /*
             * mapCategory 필드가 존재하는데 표시값으로 정규화할 수 없다면 손상 데이터다.
             * 이 경우 FE에 알 수 없는 카테고리 값을 노출하지 않고 해당 로비만 목록에서 제외한다.
             *
             * mapCategory 필드가 없거나 blank인 경우는 맵 미선택 로비이므로 제외하지 않는다.
             */
            if (hasMapCategory(data) && displayMapCategory == null) {
                continue;
            }

            result.add(LobbyRedisDto.builder()
                    .code((String) data.get(RedisKeys.FIELD_CODE))
                    .hostId((String) data.get(RedisKeys.FIELD_HOST_USER_ID))
                    .title((String) data.get(RedisKeys.FIELD_TITLE))
                    .status((String) data.get(RedisKeys.FIELD_STATUS))
                    .createdAtEpochMillis(parseNullableLong(data.get(RedisKeys.FIELD_CREATED_AT_EPOCH_MILLIS)))
                    .mapId(parseNullableLong(data.get(RedisKeys.FIELD_MAP_ID)))
                    .mapTitle((String) data.get(RedisKeys.FIELD_MAP_TITLE))
                    .mapCategory(displayMapCategory)
                    .maxPlayers(parseNullableInt(data.get(RedisKeys.FIELD_MAX_PLAYERS)))
                    .currentPlayers(currentPlayers)
                    .isPrivate(Boolean.parseBoolean((String) data.get(RedisKeys.FIELD_IS_PRIVATE)))
                    .build());
        }

        return result;
    }

    /**
     * 공개 로비 인덱스에서 특정 로비 코드를 제거한다.
     *
     * [정리 대상]
     * - lobby:public Set
     * - lobby:public:latest ZSET
     * - lobby:public:most_players ZSET
     * - lobby:public:most_available ZSET
     *
     * [사용 상황]
     * - stale index 배치 스캐너가 lobby:{code} Hash가 없는 code를 정리할 때
     * - 운영성 보정 로직에서 명시적으로 공개 인덱스를 제거할 때
     *
     * [주의]
     * 정상 로비 삭제/폭파 경로에서는 Lua 스크립트가 원자적으로 SREM/ZREM을 수행해야 한다.
     * 이 메서드는 정상 상태 전이 경로가 아니라 보정/정리 경로에서만 사용한다.
     */
    public void removePublicLobbyIndexes(String lobbyCode) {
        if (lobbyCode == null || lobbyCode.isBlank()) {
            return;
        }

        redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_LATEST, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, lobbyCode);

        log.warn(
                "공개 로비 stale index 정리 - lobbyCode: {}",
                lobbyCode
        );
    }

    /**
     * 초대 코드로 로비 입장에 필요한 정보를 조회한다.
     *
     * [조회 전략]
     * HGETALL로 lobby:{code} Hash를 한 번에 읽어 응답 객체를 구성한다.
     * currentPlayers는 lobby:{code}.current_players를 우선 사용하고,
     * 기존 Redis 데이터 호환을 위해 없으면 participants Set의 SCARD로 fallback한다.
     *
     * [반환 정책]
     * 로비가 존재하지 않으면 Optional.empty()를 반환한다.
     * 서비스 레이어에서 empty 여부로 404를 처리하므로, Repository는 존재 여부 판단만 수행한다.
     *
     * [mapCategory 방어 정책]
     * 단건 조회에서는 로비 자체를 제외할 수 없으므로,
     * 알 수 없는 mapCategory 값은 null로 변환하여 FE에 비정상 값이 노출되지 않도록 한다.
     *
     * @param inviteCode 로비 초대 코드
     * @return 로비 정보 Optional
     */
    public Optional<JoinLobbyResponse> findByInviteCode(String inviteCode) {
        Map<Object, Object> data =
                redisTemplate.opsForHash().entries(RedisKeys.lobbyKey(inviteCode));

        if (data.isEmpty()) {
            return EMPTY_LOBBY;
        }

        int currentPlayers = getCurrentPlayerCount(inviteCode);

        int maxPlayers = parseRequiredPositiveInt(
                data.get(RedisKeys.FIELD_MAX_PLAYERS),
                RedisKeys.FIELD_MAX_PLAYERS,
                inviteCode
        );

        return Optional.of(JoinLobbyResponse.builder()
                .inviteCode(inviteCode)
                .title((String) data.get(RedisKeys.FIELD_TITLE))
                .hostId((String) data.get(RedisKeys.FIELD_HOST_USER_ID))
                .maxPlayers(maxPlayers)
                .currentPlayers(currentPlayers)
                .status((String) data.get(RedisKeys.FIELD_STATUS))
                .mapId(parseNullableLong(data.get(RedisKeys.FIELD_MAP_ID)))
                .mapTitle((String) data.get(RedisKeys.FIELD_MAP_TITLE))
                .mapCategory(toDisplayMapCategoryOrNull(
                        inviteCode,
                        (String) data.get(RedisKeys.FIELD_MAP_CATEGORY)
                ))
                .build());
    }

    /**
     * 해당 로비의 현재 참여 인원 수를 반환한다.
     *
     * [조회 우선순위]
     * 1. lobby:{code}.current_players
     * 2. 기존 Redis 데이터 호환을 위한 lobby:{code}:participants SCARD
     *
     * [주의]
     * current_players는 Lua에서 관리하는 캐시 값이다.
     * Java 조회 경로에서는 누락 시 fallback만 수행하고 직접 복구 쓰기는 하지 않는다.
     *
     * @param inviteCode 로비 초대 코드
     * @return 현재 참여 인원 수
     */
    public int getCurrentPlayerCount(String inviteCode) {
        Object cachedCurrentPlayers = redisTemplate.opsForHash()
                .get(RedisKeys.lobbyKey(inviteCode), RedisKeys.FIELD_CURRENT_PLAYERS);

        Integer parsedCurrentPlayers = parseNullableInt(cachedCurrentPlayers);

        if (parsedCurrentPlayers != null) {
            return parsedCurrentPlayers;
        }

        Long count = redisTemplate.opsForSet()
                .size(RedisKeys.lobbyParticipantsKey(inviteCode));

        return count != null ? count.intValue() : 0;
    }

    /**
     * 공개 로비 목록 응답에 사용할 현재 참여 인원 수를 결정한다.
     *
     * [우선순위]
     * 1. lobby:{code} Hash의 current_players 필드
     * 2. 기존 Redis 데이터 호환을 위한 participants Set SCARD pipeline 결과
     *
     * [설계 이유]
     * current_players는 4단계부터 Lua에서 관리하는 캐시 필드다.
     * 다만 배포 전에 생성된 기존 로비에는 해당 필드가 없을 수 있으므로,
     * 조회 안정성을 위해 SCARD 결과를 fallback으로 사용한다.
     *
     * [주의]
     * fallback은 읽기 호환성만 제공한다.
     * Java에서 current_players를 직접 HSET하지 않는다.
     * current_players의 갱신 책임은 participants 변경 Lua 스크립트에 있다.
     */
    private Integer resolveCurrentPlayers(
            Map<Object, Object> data,
            List<Object> pipelineResults,
            int countResultIndex,
            String lobbyCode
    ) {
        Integer cachedCurrentPlayers = parseNullableInt(data.get(RedisKeys.FIELD_CURRENT_PLAYERS));

        if (cachedCurrentPlayers != null) {
            return cachedCurrentPlayers;
        }

        log.debug(
                "current_players 필드 없음 - SCARD fallback 사용. lobbyCode: {}",
                lobbyCode
        );

        return extractCurrentPlayerCount(
                pipelineResults,
                countResultIndex,
                lobbyCode
        );
    }

    /**
     * StringRedisTemplate의 pipeline low-level connection에서 사용할 raw key를 생성한다.
     *
     * StringRedisTemplate은 일반 ops API에서는 String 직렬화를 자동 적용하지만,
     * RedisConnection을 직접 사용하는 pipeline 내부에서는 byte[] key를 명시해야 한다.
     */
    private byte[] rawKey(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * pipeline 결과에서 HGETALL 결과를 안전하게 꺼낸다.
     *
     * [pipeline 결과 순서]
     * - index 0: 첫 번째 로비 HGETALL
     * - index 1: 첫 번째 로비 SCARD
     * - index 2: 두 번째 로비 HGETALL
     * - index 3: 두 번째 로비 SCARD
     *
     * Spring Data Redis는 StringRedisTemplate의 value serializer를 적용해
     * Hash 결과를 Map<Object, Object> 형태로 역직렬화한다.
     */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> extractHashResult(
            List<Object> pipelineResults,
            int index,
            String lobbyCode
    ) {
        if (index >= pipelineResults.size()) {
            log.warn(
                    "Redis pipeline HGETALL 결과 누락 - lobbyCode: {}, resultIndex: {}",
                    lobbyCode,
                    index
            );
            return Map.of();
        }

        Object result = pipelineResults.get(index);
        if (result == null) {
            return Map.of();
        }

        if (result instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }

        log.warn(
                "Redis pipeline HGETALL 결과 타입 불일치 - lobbyCode: {}, resultType: {}",
                lobbyCode,
                result.getClass().getName()
        );
        return Map.of();
    }

    /**
     * pipeline 결과에서 SCARD 결과를 안전하게 꺼낸다.
     *
     * Redis SCARD 결과는 일반적으로 Long으로 역직렬화된다.
     * 예상하지 못한 타입이 들어오면 null을 반환하여 Service 계층의 capacity 방어 필터가 제외하도록 한다.
     */
    private Integer extractCurrentPlayerCount(
            List<Object> pipelineResults,
            int index,
            String lobbyCode
    ) {
        if (index >= pipelineResults.size()) {
            log.warn(
                    "Redis pipeline SCARD 결과 누락 - lobbyCode: {}, resultIndex: {}",
                    lobbyCode,
                    index
            );
            return null;
        }

        Object result = pipelineResults.get(index);
        if (result == null) {
            return null;
        }

        if (result instanceof Number count) {
            return count.intValue();
        }

        log.warn(
                "Redis pipeline SCARD 결과 타입 불일치 - lobbyCode: {}, resultType: {}",
                lobbyCode,
                result.getClass().getName()
        );
        return null;
    }

    private Long parseNullableLong(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Long.parseLong((String) value);
        } catch (NumberFormatException e) {
            log.warn("Redis Hash 필드 Long 파싱 실패 - 값: {}", value);
            return null;
        }
    }

    private Integer parseNullableInt(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Integer.parseInt((String) value);
        } catch (NumberFormatException e) {
            log.warn("Redis Hash 필드 Integer 파싱 실패 - 값: {}", value);
            return null;
        }
    }

    /**
     * Redis에서 읽은 mapCategory 값을 FE 응답 표시값으로 변환한다.
     *
     * [정책]
     * - null 또는 blank는 맵 미선택 상태로 보고 null로 반환한다.
     * - 정상 값은 MapCategory의 단일 정규화 규칙을 사용해 표시값으로 변환한다.
     * - 알 수 없는 값은 Redis 데이터 손상으로 보고 null을 반환한다.
     *
     * [주의]
     * 잘못된 값을 원본 그대로 반환하면 FE가 알 수 없는 카테고리를 받게 된다.
     * 따라서 Repository 경계에서 허용된 표시값 또는 null만 반환하도록 제한한다.
     *
     * @param lobbyCode 로비 코드. 로그 추적용
     * @param rawCategory Redis Hash에서 읽은 원본 카테고리 값
     * @return FE 응답에 사용할 카테고리 표시값. 변환 불가 시 null
     */
    private String toDisplayMapCategoryOrNull(String lobbyCode, String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return null;
        }

        try {
            return MapCategory.toDisplayValue(rawCategory);
        } catch (IllegalArgumentException e) {
            log.warn(
                    "알 수 없는 맵 카테고리 값 - lobbyCode: {}, rawCategory: {}",
                    lobbyCode,
                    rawCategory,
                    e
            );
            return null;
        }
    }

    /**
     * Redis 로비 Hash에 mapCategory 필드가 실제로 존재하는지 확인한다.
     *
     * [필요 이유]
     * mapCategory가 없는 로비는 맵 미선택 로비로 허용할 수 있다.
     * 반면 mapCategory 필드는 존재하지만 값이 알 수 없는 경우는 Redis 손상 데이터로 보고 제외해야 한다.
     */
    private boolean hasMapCategory(Map<Object, Object> data) {
        Object rawCategory = data.get(RedisKeys.FIELD_MAP_CATEGORY);
        return rawCategory instanceof String category && !category.isBlank();
    }

    /**
     * Redis Hash의 필수 양수 정수 필드를 파싱한다.
     *
     * [사용 목적]
     * max_players처럼 로비 입장 검증에 반드시 필요한 필드는
     * 누락되거나 잘못된 값일 때 기본값으로 폴백하면 안 된다.
     *
     * [실패 처리]
     * - null
     * - 숫자 파싱 실패
     * - 0 이하
     *
     * 위 경우는 Redis 로비 데이터 손상으로 보고 500을 반환한다.
     *
     * @param value Redis Hash에서 조회한 원시값
     * @param fieldName Redis Hash 필드명
     * @param inviteCode 로비 초대 코드
     * @return 파싱된 양수 정수
     */
    private int parseRequiredPositiveInt(Object value, String fieldName, String inviteCode) {
        if (value == null) {
            log.error(
                    "Redis 로비 필수 필드 누락 - inviteCode: {}, field: {}",
                    inviteCode,
                    fieldName
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_INVALID_LOBBY_DATA
            );
        }

        try {
            int parsed = Integer.parseInt((String) value);

            if (parsed <= 0) {
                log.error(
                        "Redis 로비 필수 필드 값이 유효하지 않음 - inviteCode: {}, field: {}, value: {}",
                        inviteCode,
                        fieldName,
                        value
                );

                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ERROR_INVALID_LOBBY_DATA
                );
            }

            return parsed;

        } catch (NumberFormatException e) {
            log.error(
                    "Redis 로비 필수 필드 숫자 파싱 실패 - inviteCode: {}, field: {}, value: {}",
                    inviteCode,
                    fieldName,
                    value,
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ERROR_INVALID_LOBBY_DATA
            );
        }
    }
}