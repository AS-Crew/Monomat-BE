-- ============================================================================
-- 게임 세션 초기화 원자적 처리 스크립트
--
-- [책임]
-- 1. game:session:{code} 해시 초기화 (라운드 수, 현재 라운드 등)
-- 2. game:session:{code}:rounds 리스트에 문제 ID 순서대로 저장
-- 3. game:session:{code}:players 해시에 참가자 초기 점수(0) 저장
-- ============================================================================

local sessionKey = KEYS[1]
local roundsKey  = KEYS[2]
local playersKey = KEYS[3]

local totalRounds = ARGV[1]
local mapItemIds  = ARGV[2] -- 쉼표로 구분된 문자열 (예: "1,4,5,2,3")
local participants = ARGV[3] -- 쉼표로 구분된 문자열 (예: "uuid1,uuid2")
local timeLimitSeconds = ARGV[4]
local roundStartedAt = ARGV[5]
local ttl = ARGV[6]

-- 0. 게임 세션이 이미 존재하는지 검사 (멱등성 보장)
if redis.call('EXISTS', sessionKey) == 1 then
    return "ERROR_ALREADY_EXISTS"
end

-- 1. 기존 잔여 데이터 초기화 (혹시 모를 가비지 데이터 정리)
redis.call('DEL', sessionKey, roundsKey, playersKey)

-- 2. 세션 메타데이터 저장
redis.call('HSET', sessionKey,
    'current_round_no', '1',
    'total_question_count', totalRounds,
    'status', 'PLAYING',
    'round_phase', 'READY',
    'time_limit_seconds', timeLimitSeconds,
    'round_started_at', roundStartedAt
)
redis.call('EXPIRE', sessionKey, ttl)

-- 3. 라운드별 MapItem ID 저장
-- mapItemIds를 분리하여 RPUSH
for id in string.gmatch(mapItemIds, '([^,]+)') do
    redis.call('RPUSH', roundsKey, id)
end
redis.call('EXPIRE', roundsKey, ttl)

-- 4. 참가자 초기 점수 저장
for uuid in string.gmatch(participants, '([^,]+)') do
    redis.call('HSET', playersKey, uuid, '0')
end
redis.call('EXPIRE', playersKey, ttl)

return "OK"
