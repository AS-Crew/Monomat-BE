---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 퇴장 및 상태 전이 스크립트
-- Redis는 싱글 스레드로 동작하므로, 이 Lua 스크립트가 실행되는 동안에는
-- 다른 명령어(다른 유저의 퇴장 등)가 끼어들 수 없어 완벽한 원자성(Atomicity)이 보장된다.
-- ============================================================================

local lobbyKey = KEYS[1]                -- 로비 메타 정보 (Hash)
local participantsKey = KEYS[2]         -- 로비 참여자 명단 (Set)
local orderKey = KEYS[3]                -- 로비 입장 순서 (List)
local kickedKey = KEYS[4]               -- 로비 강퇴 명단 (Set)
local publicListKey = KEYS[5]           -- 전역 공개 로비 목록 (Set)
local publicLatestIndexKey = KEYS[6]    -- 공개 로비 최신순 정렬 인덱스 (ZSET)

local userId = ARGV[1]                  -- 퇴장하려는 유저 ID
local lobbyCode = ARGV[2]               -- 퇴장하려는 로비 코드

-- Redis Hash 필드명
local FIELD_CURRENT_PLAYERS = 'current_players'

-- 1. 참여자 명단(Set)과 입장 순서(List)에서 해당 유저를 즉시 제거
redis.call('SREM', participantsKey, userId)
redis.call('LREM', orderKey, 1, userId)

-- 2. 유저 제거 후 남은 인원수 확인
local remainCount = redis.call('SCARD', participantsKey)

if remainCount == 0 then
    -- [Case A: 남은 인원이 0명인 경우 -> 로비 폭파]
    -- 로비가 폭파되므로 lobby Hash 자체를 삭제한다.
    -- 따라서 current_players를 별도로 0으로 갱신할 필요가 없다.
    redis.call('DEL', lobbyKey, participantsKey, orderKey, kickedKey)
    redis.call('SREM', publicListKey, lobbyCode)
    redis.call('ZREM', publicLatestIndexKey, lobbyCode)
    return "DESTROYED"
else
    -- [Case B: 인원이 남아있는 경우 -> 방장 위임 여부 확인]
    -- participants Set을 기준으로 현재 인원 캐시를 재계산한다.
    -- 단순 감소가 아니라 SCARD 결과를 저장해 Redis 데이터 불일치 상황에서도 복구력을 높인다.
    redis.call('HSET', lobbyKey, FIELD_CURRENT_PLAYERS, tostring(remainCount))

    local currentHost = redis.call('HGET', lobbyKey, 'host_user_id')

    if currentHost == userId then
        local nextHost = redis.call('LINDEX', orderKey, 0)
        if nextHost then
            -- 정상 케이스 : orderKey에서 다음 방장 선정
            redis.call('HSET', lobbyKey, 'host_user_id', nextHost)
            return "DELEGATED:" .. nextHost
        else
            -- 폴백 : orderKey와 participantsKey 불일치 시 Set에서 랜덤 선정
            local fallbackHost = redis.call('SRANDMEMBER', participantsKey)
            if fallbackHost then
                redis.call('HSET', lobbyKey, 'host_user_id', fallbackHost)
                return "DELEGATED:" .. fallbackHost
            else
                -- 실질적으로 참여자가 없는 상태 -> 로비 폭파
                redis.call('DEL', lobbyKey, participantsKey, orderKey, kickedKey)
                redis.call('SREM', publicListKey, lobbyCode)
                redis.call('ZREM', publicLatestIndexKey, lobbyCode)
                return "DESTROYED"
            end
        end
    end

    -- 나간 사람이 일반 유저였거나, 위임 로직을 타지 않은 경우
    -- 즉, 나간 유저가 방장이 아닌 일반 유저인 경우
    return "LEFT"
end