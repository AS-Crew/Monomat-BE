local sessionKey = KEYS[1]
local correctPlayersKey = KEYS[2]
local roundDataKey = KEYS[3]
local correctTimesKey = KEYS[4]

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

-- 2.5 이미 종료된 라운드인지 검증 (round_ended_at 존재 여부 확인)
local endedField = 'round_ended_at:' .. requestRoundNo
if redis.call('HEXISTS', sessionKey, endedField) == 1 then
    return 'ROUND_ALREADY_ENDED'
end

-- 3. 시간 초과 검증 (지연 완충 시간 1.5초 고려)
-- Java RedisKeys.gameSessionRoundPlaybackStartedAtField() 계약과 일치하는 필드명 사용
local playbackStartedKey = 'playback_started_at:' .. requestRoundNo
local playbackStartedAtStr = redis.call('HGET', sessionKey, playbackStartedKey)
local timeLimitStr = redis.call('HGET', sessionKey, 'time_limit_seconds')

-- 라운드 재생 시작 시간이나 제한 시간 설정이 아직 기록되지 않은 경우 정답 제출 거부
if not playbackStartedAtStr or not timeLimitStr then
    return 'ROUND_NOT_STARTED'
end

local playbackStartedAt = tonumber(playbackStartedAtStr)
local timeLimitSeconds = tonumber(timeLimitStr)
local limitTimeMillis = playbackStartedAt + (timeLimitSeconds * 1000) + 1500
if nowMillis > limitTimeMillis then
    return 'TIMEOUT'
end

-- 4. 중복 정답 여부 검증
local isMember = redis.call('SISMEMBER', correctPlayersKey, userIdentifier)
if isMember == 1 then
    return 'ALREADY_CORRECT'
end

-- 5. 정답 시간 기록 및 정답자 등록
redis.call('HSET', correctTimesKey, userIdentifier, tostring(nowMillis))
redis.call('SADD', correctPlayersKey, userIdentifier)

-- 6. 최초 정답자(1등) 여부 판별 (HSETNX)
local isFirst = redis.call('HSETNX', roundDataKey, 'first_correct_user_id', userIdentifier)
if isFirst == 1 then
    return 'CORRECT_FIRST_PLACE'
else
    return 'CORRECT'
end

