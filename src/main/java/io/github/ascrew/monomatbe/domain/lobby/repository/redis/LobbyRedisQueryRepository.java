package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

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
     * lobby:public Set에 들어 있는 초대 코드를 기준으로 lobby:{code} Hash를 조회한다.
     *
     * [주의]
     * lobby:public에는 남아 있지만 lobby:{code} Hash가 TTL 만료 등으로 사라진 경우는 응답에서 제외한다.
     *
     * [mapCategory 방어 정책]
     * - mapCategory가 없거나 blank이면 맵 미선택 로비로 보고 목록에 포함한다.
     * - mapCategory가 정상 값이면 FE 표시값(K-POP, J-POP, POP)으로 변환한다.
     * - mapCategory 필드는 존재하지만 정규화할 수 없는 값이면 Redis 손상 데이터로 보고 목록에서 제외한다.
     *
     * @return 공개 로비 목록
     */
    public List<LobbyRedisDto> getPublicLobbies() {
        Set<String> publicLobbyCodes =
                redisTemplate.opsForSet().members(RedisKeys.LOBBY_PUBLIC);

        if (publicLobbyCodes == null || publicLobbyCodes.isEmpty()) {
            return new ArrayList<>();
        }

        List<LobbyRedisDto> result = new ArrayList<>();

        for (String code : publicLobbyCodes) {
            Map<Object, Object> data =
                    redisTemplate.opsForHash().entries(RedisKeys.lobbyKey(code));

            if (data.isEmpty()) {
                continue;
            }

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
                    .currentPlayers(getCurrentPlayerCount(code))
                    .isPrivate(Boolean.parseBoolean((String) data.get(RedisKeys.FIELD_IS_PRIVATE)))
                    .build());
        }

        return result;
    }

    /**
     * 초대 코드로 로비 입장에 필요한 정보를 조회한다.
     *
     * [조회 전략]
     * HGETALL로 lobby:{code} Hash를 한 번에 읽어 응답 객체를 구성한다.
     * currentPlayers는 participants Set의 SCARD로 별도 조회한다.
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
     * [구현 방식]
     * lobby:{code}:participants Set의 SCARD 명령으로 조회한다.
     * null 반환 시 Redis 연결 이상일 수 있으므로 0으로 폴백하여 NPE를 방지한다.
     *
     * @param inviteCode 로비 초대 코드
     * @return 현재 참여 인원 수
     */
    public int getCurrentPlayerCount(String inviteCode) {
        Long count = redisTemplate.opsForSet()
                .size(RedisKeys.lobbyParticipantsKey(inviteCode));

        return count != null ? count.intValue() : 0;
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