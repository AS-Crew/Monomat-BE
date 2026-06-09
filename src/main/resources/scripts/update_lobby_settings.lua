-- ============================================================================
-- update_lobby_settings.lua
--
-- [목적]
-- 로비 설정 변경 시 현재 참가자 수 검증과 Redis Hash 갱신을 원자적으로 처리한다.
--
-- [KEYS]
-- KEYS[1] = lobby:{code}
-- KEYS[2] = lobby:{code}:participants
--
-- [ARGV]
-- ARGV[1] = status field name
-- ARGV[2] = max_players field name
-- ARGV[3] = question_count field name
-- ARGV[4] = time_limit_seconds field name
-- ARGV[5] = WAITING status
-- ARGV[6] = maxPlayers
-- ARGV[7] = questionCount
-- ARGV[8] = timeLimitSeconds
--
-- [Return]
-- UPDATED
-- LOBBY_NOT_FOUND
-- NOT_WAITING
-- MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS
-- ============================================================================

local lobbyKey = KEYS[1]
local participantsKey = KEYS[2]

local statusField = ARGV[1]
local maxPlayersField = ARGV[2]
local questionCountField = ARGV[3]
local timeLimitSecondsField = ARGV[4]
local waitingStatus = ARGV[5]

local maxPlayers = tonumber(ARGV[6])
local questionCount = ARGV[7]
local timeLimitSeconds = ARGV[8]

if redis.call('EXISTS', lobbyKey) == 0 then
    return 'LOBBY_NOT_FOUND'
end

local currentStatus = redis.call('HGET', lobbyKey, statusField)
if currentStatus ~= waitingStatus then
    return 'NOT_WAITING'
end

local currentPlayers = redis.call('SCARD', participantsKey)
if currentPlayers > maxPlayers then
    return 'MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS'
end

redis.call(
    'HSET',
    lobbyKey,
    maxPlayersField,
    tostring(maxPlayers),
    questionCountField,
    questionCount,
    timeLimitSecondsField,
    timeLimitSeconds
)

return 'UPDATED'