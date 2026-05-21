---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 퇴장 및 상태 전이 스크립트
--
-- [책임]
-- 1. participants Set / order List에서 퇴장 유저 제거
-- 2. 남은 인원이 없으면 로비 폭파 및 공개 인덱스 정리
-- 3. 방장이 퇴장한 경우 새 방장 위임
-- 4. current_players 캐시와 공개 로비 인원 기준 ZSET 인덱스 갱신
--
-- [중요]
-- Redis Lua는 원자적으로 실행되므로 스크립트 중간 상태가 외부에 노출되지 않는다.
-- 다만 최종 상태가 확정된 뒤 current_players/ZSET을 갱신해야 로직 추론이 명확해진다.
-- ============================================================================

local lobbyKey = KEYS[1]                      -- 로비 메타 정보 (Hash)
local participantsKey = KEYS[2]               -- 로비 참여자 명단 (Set)
local orderKey = KEYS[3]                      -- 로비 입장 순서 (List)
local kickedKey = KEYS[4]                     -- 로비 강퇴 명단 (Set)
local publicListKey = KEYS[5]                 -- 전역 공개 로비 목록 (Set)
local publicLatestIndexKey = KEYS[6]          -- 공개 로비 최신순 정렬 인덱스 (ZSET)
local publicMostPlayersIndexKey = KEYS[7]     -- 공개 로비 현재 인원 많은 순 정렬 인덱스 (ZSET)
local publicMostAvailableIndexKey = KEYS[8]   -- 공개 로비 빈자리 많은 순 정렬 인덱스 (ZSET)

local userId = ARGV[1]                        -- 퇴장하려는 유저 ID
local lobbyCode = ARGV[2]                     -- 퇴장하려는 로비 코드

-- Redis Hash 필드명
local FIELD_HOST_USER_ID = 'host_user_id'
local FIELD_CURRENT_PLAYERS = 'current_players'
local FIELD_MAX_PLAYERS = 'max_players'
local FIELD_IS_PRIVATE = 'is_private'

-- 공개 로비 인덱스에서 현재 로비를 제거한다.
-- 로비 폭파 또는 Hash 삭제 시 모든 공개 인덱스에서 함께 제거해야 stale index가 남지 않는다.
local function removePublicIndexes()
    redis.call('SREM', publicListKey, lobbyCode)
    redis.call('ZREM', publicLatestIndexKey, lobbyCode)
    redis.call('ZREM', publicMostPlayersIndexKey, lobbyCode)
    redis.call('ZREM', publicMostAvailableIndexKey, lobbyCode)
end

-- current_players 캐시와 인원 기준 공개 정렬 인덱스를 갱신한다.
--
-- [정책]
-- - current_players는 participants Set의 SCARD 결과를 저장한다.
-- - 비공개 로비는 공개 ZSET에 절대 추가하지 않는다.
-- - 공개 로비인데 max_players가 없거나 비정상 값이면 Redis 로비 데이터 손상으로 보고 오류를 반환한다.
--
-- [주의]
-- 이 함수는 participants 변경 및 방장 위임이 끝난 뒤 호출한다.
local function updatePublicCapacityIndexes(currentPlayers)
    redis.call('HSET', lobbyKey, FIELD_CURRENT_PLAYERS, tostring(currentPlayers))

    local isPrivate = redis.call('HGET', lobbyKey, FIELD_IS_PRIVATE)

    if isPrivate ~= 'false' then
        return "OK"
    end

    local maxPlayersForIndex = tonumber(redis.call('HGET', lobbyKey, FIELD_MAX_PLAYERS))

    if maxPlayersForIndex == nil or maxPlayersForIndex <= 0 then
        return "INVALID_LOBBY_CAPACITY"
    end

    local availableSeats = maxPlayersForIndex - currentPlayers

    if availableSeats < 0 then
        availableSeats = 0
    end

    redis.call('ZADD', publicMostPlayersIndexKey, currentPlayers, lobbyCode)
    redis.call('ZADD', publicMostAvailableIndexKey, availableSeats, lobbyCode)

    return "OK"
end

-- 1. 참여자 명단(Set)과 입장 순서(List)에서 해당 유저를 제거한다.
redis.call('SREM', participantsKey, userId)
redis.call('LREM', orderKey, 1, userId)

-- 2. 유저 제거 후 남은 인원수를 확인한다.
local remainCount = redis.call('SCARD', participantsKey)

if remainCount == 0 then
    -- [Case A] 남은 인원이 없으면 로비를 폭파한다.
    redis.call('DEL', lobbyKey, participantsKey, orderKey, kickedKey)
    removePublicIndexes()
    return "DESTROYED"
end

-- 3. 남은 인원이 있으면 방장 위임 여부를 먼저 확정한다.
local currentHost = redis.call('HGET', lobbyKey, FIELD_HOST_USER_ID)

if currentHost == userId then
    local nextHost = redis.call('LINDEX', orderKey, 0)

    if nextHost then
        -- 정상 케이스: order List에서 다음 방장을 선정한다.
        redis.call('HSET', lobbyKey, FIELD_HOST_USER_ID, nextHost)

        local indexUpdateResult = updatePublicCapacityIndexes(remainCount)
        if indexUpdateResult ~= "OK" then
            return indexUpdateResult
        end

        return "DELEGATED:" .. nextHost
    end

    -- 폴백: order List와 participants Set이 불일치하는 경우 Set에서 랜덤 선정한다.
    local fallbackHost = redis.call('SRANDMEMBER', participantsKey)

    if fallbackHost then
        redis.call('HSET', lobbyKey, FIELD_HOST_USER_ID, fallbackHost)

        local indexUpdateResult = updatePublicCapacityIndexes(remainCount)
        if indexUpdateResult ~= "OK" then
            return indexUpdateResult
        end

        return "DELEGATED:" .. fallbackHost
    end

    -- 이론상 remainCount > 0이면 여기로 오면 안 된다.
    -- 다만 Redis 자료구조 불일치 상황에서는 안전하게 로비를 폭파하고 모든 공개 인덱스를 제거한다.
    redis.call('DEL', lobbyKey, participantsKey, orderKey, kickedKey)
    removePublicIndexes()
    return "DESTROYED"
end

-- 4. 일반 유저가 나간 경우에는 방장 위임 없이 current_players/ZSET만 갱신한다.
local indexUpdateResult = updatePublicCapacityIndexes(remainCount)
if indexUpdateResult ~= "OK" then
    return indexUpdateResult
end

return "LEFT"