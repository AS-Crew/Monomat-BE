---@diagnostic disable: undefined-global
--
-- [역할]
-- 로비 맵 변경 트랜잭션 보상 복구 전용 스크립트.
-- DB 갱신 실패 또는 0행 반환 시 호출자가 Redis 맵 메타데이터를 이전 값으로 되돌리려고 한다.
-- 그러나 그 사이 start_lobby.lua에 의해 status가 이미 PLAYING으로 전환되었을 수 있으므로,
-- 반드시 status == WAITING일 때만 원자적으로 복구한다.
--
-- [정책]
-- - status != WAITING (PLAYING 등) → 'SKIPPED_NOT_WAITING' 반환. Redis 맵 메타데이터 변경 없음.
-- - oldMapId가 빈 문자열 → 이전이 "맵 미선택" 상태였음. map_id/map_title/map_category HDEL.
-- - oldMapId가 있음 → 3개 필드 HSET.
--
-- [KEYS]
-- KEYS[1] = lobby:{code}
--
-- [ARGV]
-- ARGV[1] = fieldStatus
-- ARGV[2] = fieldMapId
-- ARGV[3] = fieldMapTitle
-- ARGV[4] = fieldMapCategory
-- ARGV[5] = waitingStatus
-- ARGV[6] = oldMapId or ""
-- ARGV[7] = oldMapTitle or ""
-- ARGV[8] = oldMapCategory or ""

local lobbyKey = KEYS[1]

local fieldStatus = ARGV[1]
local fieldMapId = ARGV[2]
local fieldMapTitle = ARGV[3]
local fieldMapCategory = ARGV[4]
local waitingStatus = ARGV[5]
local oldMapId = ARGV[6]
local oldMapTitle = ARGV[7]
local oldMapCategory = ARGV[8]

if redis.call('EXISTS', lobbyKey) == 0 then
    return 'LOBBY_NOT_FOUND'
end

local currentStatus = redis.call('HGET', lobbyKey, fieldStatus)

if currentStatus ~= waitingStatus then
    return 'SKIPPED_NOT_WAITING'
end

if oldMapId == nil or oldMapId == '' then
    redis.call('HDEL', lobbyKey, fieldMapId, fieldMapTitle, fieldMapCategory)
else
    redis.call('HSET', lobbyKey, fieldMapId, oldMapId, fieldMapTitle, oldMapTitle, fieldMapCategory, oldMapCategory)
end

return 'COMPENSATED'
