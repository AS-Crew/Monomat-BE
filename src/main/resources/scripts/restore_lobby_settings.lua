-- ============================================================================
-- restore_lobby_settings.lua
--
-- [목적]
-- DB 로비 설정 갱신 실패 시 Redis 설정값 복구를 원자적으로 처리한다.
--
-- [KEYS]
-- KEYS[1] = lobby:{code}
-- KEYS[2] = lobby:{code}:participants
-- KEYS[3] = lobby:public:most_available
--
-- [ARGV]
-- ARGV[1]  = status field name
-- ARGV[2]  = max_players field name
-- ARGV[3]  = question_count field name
-- ARGV[4]  = time_limit_seconds field name
-- ARGV[5]  = WAITING status
-- ARGV[6]  = is_private field name
-- ARGV[7]  = current_players field name
-- ARGV[8]  = lobby code
-- ARGV[9]  = restore maxPlayers
-- ARGV[10] = restore questionCount
-- ARGV[11] = restore timeLimitSeconds
--
-- [Return]
-- RESTORED
-- LOBBY_NOT_FOUND
-- NOT_WAITING
-- MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS
-- ============================================================================

local lobbyKey = KEYS[1]
local participantsKey = KEYS[2]
local publicMostAvailableIndexKey = KEYS[3]

local statusField = ARGV[1]
local maxPlayersField = ARGV[2]
local questionCountField = ARGV[3]
local timeLimitSecondsField = ARGV[4]
local waitingStatus = ARGV[5]
local isPrivateField = ARGV[6]
local currentPlayersField = ARGV[7]
local lobbyCode = ARGV[8]

local restoreMaxPlayers = tonumber(ARGV[9])
local restoreQuestionCount = ARGV[10]
local restoreTimeLimitSeconds = ARGV[11]

if redis.call('EXISTS', lobbyKey) == 0 then
    return 'LOBBY_NOT_FOUND'
end

local currentStatus = redis.call('HGET', lobbyKey, statusField)
if currentStatus ~= waitingStatus then
    return 'NOT_WAITING'
end

local currentPlayers = redis.call('SCARD', participantsKey)
if currentPlayers > restoreMaxPlayers then
    return 'MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS'
end

redis.call(
    'HSET',
    lobbyKey,
    maxPlayersField,
    tostring(restoreMaxPlayers),
    questionCountField,
    restoreQuestionCount,
    timeLimitSecondsField,
    restoreTimeLimitSeconds,
    currentPlayersField,
    tostring(currentPlayers)
)

local isPrivate = redis.call('HGET', lobbyKey, isPrivateField)
if isPrivate == 'false' then
    local availableSeats = restoreMaxPlayers - currentPlayers
    if availableSeats < 0 then
        availableSeats = 0
    end

    redis.call('ZADD', publicMostAvailableIndexKey, availableSeats, lobbyCode)
end

return 'RESTORED'