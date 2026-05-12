---@diagnostic disable: undefined-global
--
-- [역할]
-- 로비 게임 시작 조건 중 Redis 기준으로 원자 검증 가능한 조건을 확인하고,
-- 조건 충족 시 로비 상태를 PLAYING으로 변경한다.
--
-- [검증 조건]
-- 1. 로비 존재
-- 2. 방장 정보 존재
-- 3. 요청자가 방장
-- 4. 로비 상태가 WAITING
-- 5. 맵이 선택되어 있음
-- 6. 방장 제외 참여자가 1명 이상 존재
-- 7. 방장 제외 모든 참여자가 ready 상태
--
-- [KEYS]
-- KEYS[1] = lobby:{code}
-- KEYS[2] = lobby:{code}:participants
-- KEYS[3] = lobby:{code}:ready
-- KEYS[4] = lobby:public
--
-- [ARGV]
-- ARGV[1] = requesterIdentifier
-- ARGV[2] = lobbyCode
-- ARGV[3] = fieldHostUserId
-- ARGV[4] = fieldStatus
-- ARGV[5] = fieldMapId
-- ARGV[6] = waitingStatus
-- ARGV[7] = playingStatus

local lobbyKey = KEYS[1]
local participantsKey = KEYS[2]
local readyKey = KEYS[3]
local publicLobbyKey = KEYS[4]

local requesterIdentifier = ARGV[1]
local lobbyCode = ARGV[2]
local fieldHostUserId = ARGV[3]
local fieldStatus = ARGV[4]
local fieldMapId = ARGV[5]
local waitingStatus = ARGV[6]
local playingStatus = ARGV[7]

if redis.call('EXISTS', lobbyKey) == 0 then
    return 'LOBBY_NOT_FOUND'
end

local hostUserId = redis.call('HGET', lobbyKey, fieldHostUserId)

if hostUserId == false or hostUserId == nil or hostUserId == '' then
    return 'HOST_NOT_FOUND'
end

if hostUserId ~= requesterIdentifier then
    return 'FORBIDDEN'
end

local status = redis.call('HGET', lobbyKey, fieldStatus)

if status ~= waitingStatus then
    return 'LOBBY_NOT_WAITING'
end

local mapId = redis.call('HGET', lobbyKey, fieldMapId)

if mapId == false or mapId == nil or mapId == '' then
    return 'MAP_NOT_SELECTED'
end

local participants = redis.call('SMEMBERS', participantsKey)
local nonHostPlayerCount = 0

for _, userIdentifier in ipairs(participants) do
    if userIdentifier ~= hostUserId then
        nonHostPlayerCount = nonHostPlayerCount + 1

        if redis.call('SISMEMBER', readyKey, userIdentifier) == 0 then
            return 'NOT_READY:' .. userIdentifier
        end
    end
end

if nonHostPlayerCount == 0 then
    return 'NO_PLAYER'
end

redis.call('HSET', lobbyKey, fieldStatus, playingStatus)
redis.call('SREM', publicLobbyKey, lobbyCode)

return 'STARTED'