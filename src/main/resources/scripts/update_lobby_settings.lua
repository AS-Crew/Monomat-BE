-- ============================================================================
-- update_lobby_settings.lua
--
-- [목적]
-- 로비 설정 변경 시 현재 참가자 수 검증, Redis Hash 갱신,
-- 공개 로비 빈자리 정렬 인덱스 갱신을 원자적으로 처리한다.
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
-- ARGV[9]  = maxPlayers
-- ARGV[10] = questionCount
-- ARGV[11] = timeLimitSeconds
--
-- [Return]
-- UPDATED
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

local maxPlayers = tonumber(ARGV[9])
local questionCount = ARGV[10]
local timeLimitSeconds = ARGV[11]

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
    timeLimitSeconds,
    currentPlayersField,
    tostring(currentPlayers)
)

local isPrivate = redis.call('HGET', lobbyKey, isPrivateField)
if isPrivate == 'false' then
    local availableSeats = maxPlayers - currentPlayers
    if availableSeats < 0 then
        availableSeats = 0
    end

    redis.call('ZADD', publicMostAvailableIndexKey, availableSeats, lobbyCode)
end

return 'UPDATED'