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

-- participants Set을 현재 로비 참여자의 source of truth로 사용한다.
--
-- ready Set에만 남아 있는 잔여 데이터는 여기서 순회하지 않으므로 게임 시작 조건에 영향을 주지 않는다.
-- 반대로 participants에 남아 있는데 ready Set에 없는 유저는 "아직 준비하지 않은 현재 참여자"로 판단한다.
--
-- Lua 단계에서 participants/ready 불일치를 자동 보정하지 않는 이유:
-- participants에 존재하고 ready에 없는 유저는 실제 미준비 참여자일 수 있으므로,
-- start_lobby.lua가 임의로 제거하면 정상 참여자를 잘못 삭제할 수 있다.
--
-- 퇴장/강퇴 이후 participants가 남는 비정상 상황은 leave_lobby.lua / kick_lobby.lua 처리 실패에 가깝다.
-- 해당 정합성 문제는 Repository 레벨의 MONITORING_REQUIRED 로그와 후속 재처리/운영 정리 이슈에서 다룬다.

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