local sessionKey = KEYS[1]
local correctPlayersKey = KEYS[2]

local userIdentifier = ARGV[1]
local requestRoundNo = tonumber(ARGV[2])
local nowMillis = tonumber(ARGV[3])

-- 1. 게임 세션 존재 여부 및 status 검증
local status = redis.call('HGET', sessionKey, 'status')
if not status then
    return 'NOT_FOUND'
end
if status ~= 'PLAYING' then
    return 'NOT_PLAYING'
end

-- 2. 라운드 일치 여부 검증
local currentRoundNo = tonumber(redis.call('HGET', sessionKey, 'current_round_no'))
if currentRoundNo ~= requestRoundNo then
    return 'WRONG_ROUND'
end

-- 3. 시간 초과 검증 (지연 완충 시간 1.5초 고려)
local playbackStartedKey = 'playback_started_at_round_' .. requestRoundNo
local playbackStartedAtStr = redis.call('HGET', sessionKey, playbackStartedKey)
local timeLimitStr = redis.call('HGET', sessionKey, 'time_limit_seconds')

if playbackStartedAtStr and timeLimitStr then
    local playbackStartedAt = tonumber(playbackStartedAtStr)
    local timeLimitSeconds = tonumber(timeLimitStr)
    local limitTimeMillis = playbackStartedAt + (timeLimitSeconds * 1000) + 1500
    if nowMillis > limitTimeMillis then
        return 'TIMEOUT'
    end
end

-- 4. 중복 정답 여부 검증
local isMember = redis.call('SISMEMBER', correctPlayersKey, userIdentifier)
if isMember == 1 then
    return 'ALREADY_CORRECT'
end

-- 5. 정답자 등록
redis.call('SADD', correctPlayersKey, userIdentifier)
return 'CORRECT_FIRST'
