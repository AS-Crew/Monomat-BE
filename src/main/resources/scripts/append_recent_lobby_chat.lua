-- ============================================================================
-- 로비 최근 채팅 메시지 저장 원자 처리 스크립트
--
-- [책임]
-- 1. 로비 최근 채팅 List에 메시지 append
-- 2. 최근 maxSize개만 유지
-- 3. TTL 갱신
--
-- [원자화 이유]
-- Java에서 RPUSH, LTRIM, EXPIRE를 개별 명령으로 실행하면
-- 중간 실패 시 List 길이 제한 또는 TTL 갱신이 누락될 수 있다.
-- ============================================================================

local recentChatKey = KEYS[1]

local payload = ARGV[1]
local maxSize = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

if payload == nil or payload == '' then
    return 'ERROR_EMPTY_PAYLOAD'
end

if maxSize == nil or maxSize < 1 then
    return 'ERROR_INVALID_MAX_SIZE'
end

if ttlSeconds == nil or ttlSeconds < 1 then
    return 'ERROR_INVALID_TTL'
end

redis.call('RPUSH', recentChatKey, payload)
redis.call('LTRIM', recentChatKey, -maxSize, -1)
redis.call('EXPIRE', recentChatKey, ttlSeconds)

return 'OK'