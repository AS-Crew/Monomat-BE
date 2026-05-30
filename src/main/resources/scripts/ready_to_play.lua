-- ============================================================================
-- 라운드 준비 완료(ready-to-play) 원자적 처리 스크립트
--
-- [파라미터]
-- KEYS[1] : sessionKey (예: game:session:{code})
-- KEYS[2] : readyKey (예: game:session:{code}:round:{roundNo}:ready)
-- KEYS[3] : participantsKey (예: lobby:{code}:participants)
-- KEYS[4] : playbackLockKey (예: game:session:{code}:round:{roundNo}:playback_lock)
--
-- ARGV[1] : userIdentifier
-- ARGV[2] : roundNo
-- ARGV[3] : ttl (초)
-- ============================================================================

local sessionKey = KEYS[1]
local readyKey = KEYS[2]
local participantsKey = KEYS[3]
local playbackLockKey = KEYS[4]

local userIdentifier = ARGV[1]
local roundNo = ARGV[2]
local ttl = ARGV[3]

-- 1. 유효성 검사
if redis.call('EXISTS', sessionKey) == 0 then
    return "ERROR_SESSION_NOT_FOUND"
end
if redis.call('HGET', sessionKey, 'current_round_no') ~= roundNo then
    return "ERROR_INVALID_ROUND"
end
-- 게임 세션 참가자가 아니라도 현재 로비 참가자면 수용 (이탈 방어 목적)
if redis.call('SISMEMBER', participantsKey, userIdentifier) == 0 then
    return "ERROR_NOT_PARTICIPANT"
end

-- 2. 이미 재생 시작되었는지 확인
if redis.call('EXISTS', playbackLockKey) == 1 then
    return "ALREADY_STARTED"
end

-- 3. 준비 완료 세트에 추가
redis.call('SADD', readyKey, userIdentifier)
redis.call('EXPIRE', readyKey, ttl)

local readyCount = redis.call('SCARD', readyKey)
local totalCount = redis.call('SCARD', participantsKey)

-- 4. 만약 인원이 충족되었다면 락 걸기
if readyCount >= totalCount then
    redis.call('SET', playbackLockKey, '1', 'EX', ttl)
    return "ALL_READY"
end

return "WAITING"
