-- ============================================================================
-- 게임 세션 Redis 키 통합 정리 스크립트
--
-- [책임]
-- 하나의 로비 코드에 속한 모든 game:session:{code}* 키를 원자적으로 정리한다.
--   - base 키 3종      : game:session:{code}, :rounds, :players
--   - 라운드별 키 6종   : :round:{n}:ready, :playback_lock, :data,
--                        :correct_players, :correct_times, :ended_lock
--
-- [정리 모드]
--   - DELETE : 모든 키 즉시 삭제 (로비 폭파 / 게임 시작 DB 롤백 보상)
--   - EXPIRE : 존재하는 키만 짧은 TTL로 전환 (게임 정상 종료 후 grace period)
--             ttlSeconds >= 1 필수. 위반 시(nil/0/음수) 즉시만료·스크립트 에러를
--             막기 위해 error_reply로 fail-fast한다.
--
-- [라운드 수 판별]
--   game:session:{code} 해시의 total_question_count 필드를 우선 사용하고,
--   없으면 :rounds 리스트 길이(LLEN)로 폴백한다. 둘 다 없으면 0(no-op).
--
-- [SCAN 미사용]
--   운영 중 SCAN/KEYS 패턴 매칭은 비용·블로킹 위험이 있어 사용하지 않는다.
--   대신 sessionKey 문자열로부터 모든 하위 키 이름을 결정적으로 조립한다.
--
-- [Redis Cluster / hash-tag]
--   라운드별 키는 KEYS로 선언하지 않고 sessionKey(KEYS[1])에서 조립해 직접 접근한다.
--   sessionKey는 game:session:{code} 형태로 {code} hash-tag가 적용되어 있어(RedisKeys.gameSessionKey),
--   여기서 조립되는 모든 하위 키도 동일 hash-tag를 상속한다 → 클러스터에서도 같은 슬롯에 모인다.
--   (참고: lobby 패밀리 키 hash-tag는 별도 후속 작업이며 ready_to_play.lua의 cross-family 한계는 남아 있다)
-- ============================================================================

local sessionKey = KEYS[1]

local mode = ARGV[1]            -- "DELETE" | "EXPIRE"
local ttlSeconds = tonumber(ARGV[2])

-- EXPIRE 모드 TTL 방어: 잘못된 TTL로 인한 즉시만료/스크립트 에러를 fail-fast로 차단
-- (DELETE는 ttlSeconds를 사용하지 않으므로 검증 대상에서 제외)
if mode == 'EXPIRE' then
    if ttlSeconds == nil or ttlSeconds < 1 then
        return redis.error_reply('INVALID_TTL: EXPIRE requires ttlSeconds >= 1')
    end
end

local roundsKey  = sessionKey .. ':rounds'
local playersKey = sessionKey .. ':players'

-- 라운드 수 판별: total_question_count 우선, 없으면 LLEN(:rounds) 폴백
local totalRounds = tonumber(redis.call('HGET', sessionKey, 'total_question_count'))
if totalRounds == nil then
    totalRounds = redis.call('LLEN', roundsKey)
end
if totalRounds == nil then
    totalRounds = 0
end

-- 정리 대상 키 수집 (base 3종 + 라운드별 6종)
local keys = { sessionKey, roundsKey, playersKey }
local roundSuffixes = { ':ready', ':playback_lock', ':data', ':correct_players', ':correct_times', ':ended_lock' }
for n = 1, totalRounds do
    local roundBase = sessionKey .. ':round:' .. n
    for _, suffix in ipairs(roundSuffixes) do
        table.insert(keys, roundBase .. suffix)
    end
end

local processed = 0

if mode == 'DELETE' then
    for _, key in ipairs(keys) do
        processed = processed + redis.call('DEL', key)
    end
else
    -- EXPIRE: 사전 EXISTS 체크 없이 EXPIRE 반환값으로 집계한다.
    -- (EXPIRE는 키가 없으면 0, TTL을 설정했으면 1을 반환하므로 EXISTS는 불필요한 중복 호출)
    for _, key in ipairs(keys) do
        processed = processed + redis.call('EXPIRE', key, ttlSeconds)
    end
end

return tostring(processed)
