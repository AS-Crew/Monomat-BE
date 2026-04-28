---@diagnostic disable: undefined-global
-- IDE 경고 무시용 주석 (Redis 환경에서는 변수가 자동 주입되므로 안전함)

-- ============================================================================
-- 로비 퇴장 및 상태 전이 스크립트
-- Redis는 싱글 스레드로 동작하므로, 이 Lua 스크립트가 실행되는 동안에는
-- 다른 명령어(다른 유저의 퇴장 등)가 끼어들 수 없어 완벽한 원자성(Atomicity)이 보장된다.
-- ============================================================================

local lobbyKey = KEYS[1]         -- 로비 메타 정보 (Hash)
local participantsKey = KEYS[2]  -- 로비 참여자 명단 (Set)
local orderKey = KEYS[3]         -- 로비 입장 순서 (List)
local publicListKey = KEYS[4]    -- 전역 공개 로비 목록 (Set)

local userId = ARGV[1]           -- 퇴장하려는 유저 ID
local lobbyCode = ARGV[2]        -- 퇴장하려는 로비 코드

-- 1. 참여자 명단(Set)과 입장 순서(List)에서 해당 유저를 즉시 제거
redis.call('SREM', participantsKey, userId)
redis.call('LREM', orderKey, 1, userId)

-- 2. 유저 제거 후 남은 인원수 확인
local remainCount = redis.call('SCARD', participantsKey)

if remainCount == 0 then
    -- [Case A: 남은 인원이 0명인 경우 -> 로비 폭파]
    -- 로비와 관련된 모든 키를 일괄 삭제하여 Redis 메모리 누수(좀비 방)를 방지한다.
    redis.call('DEL', lobbyKey, participantsKey, orderKey)
    redis.call('SREM', publicListKey, lobbyCode) -- 공개 방 목록에서도 제외
    return "DESTROYED"
else
    -- [Case B: 인원이 남아있는 경우 -> 방장 위임 여부 확인]
    local currentHost = redis.call('HGET', lobbyKey, 'host_user_id')

    if currentHost == userId then
        -- 나간 유저가 방장(Host)이었다면, List의 0번 인덱스(가장 먼저 들어온 다음 사람)에게 방장 이양
        local nextHost = redis.call('LINDEX', orderKey, 0)
        if nextHost then
            redis.call('HSET', lobbyKey, 'host_user_id', nextHost)
            return "DELEGATED:" .. nextHost
        end
    end
    -- 나간 사람이 일반 유저였거나, 위임 로직을 타지 않은 경우
    return "LEFT"
end