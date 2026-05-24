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

-- 1. 세션 메타데이터 저장
redis.call('HSET', sessionKey, 
    'current_round_no', '1',
    'total_round_count', totalRounds,
    'status', 'PLAYING'
)
redis.call('EXPIRE', sessionKey, 7200)

-- 2. 라운드별 MapItem ID 저장
-- mapItemIds를 분리하여 RPUSH
for id in string.gmatch(mapItemIds, '([^,]+)') do
    redis.call('RPUSH', roundsKey, id)
end
redis.call('EXPIRE', roundsKey, 7200)

-- 3. 참가자 초기 점수 저장
for uuid in string.gmatch(participants, '([^,]+)') do
    redis.call('HSET', playersKey, uuid, '0')
end
redis.call('EXPIRE', playersKey, 7200)

return "OK"
