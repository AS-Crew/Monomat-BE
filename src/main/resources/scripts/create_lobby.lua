---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 생성 원자적 처리 스크립트
--
-- SETNX 선점 + Hash/Set/List 저장을 단일 트랜잭션으로 처리하여
-- 중간 실패 시 부분 데이터가 남는 문제를 방지한다.
-- Redis는 Lua 스크립트를 싱글 스레드로 실행하므로
-- 스크립트 실행 중에는 다른 명령이 끼어들 수 없다.
-- ============================================================================

local lockKey         = KEYS[1]   -- lobby:code:lock:{code}
local lobbyKey        = KEYS[2]   -- lobby:{code}
local participantsKey = KEYS[3]   -- lobby:{code}:participants
local orderKey        = KEYS[4]   -- lobby:{code}:order
local publicListKey   = KEYS[5]   -- lobby:public

local userIdentifier  = ARGV[1]   -- 방장 식별자 (SETNX 선점자)
local lockTtlMs       = ARGV[2]   -- 락 TTL (밀리초)
local inviteCode      = ARGV[3]   -- 초대 코드
local title           = ARGV[4]   -- 로비 제목
local maxPlayers      = ARGV[5]   -- 최대 인원
local isPrivate       = ARGV[6]   -- "true" | "false"
local status          = ARGV[7]   -- "WAITING"

-- 1. SETNX 선점 시도
--    이미 동일한 코드가 선점되어 있으면 LOCK_FAILED 반환
local acquired = redis.call('SET', lockKey, userIdentifier, 'NX', 'PX', lockTtlMs)

if acquired == false then
    return "LOCK_FAILED"
end

-- 2. 로비 메타 정보 Hash 저장 (lobby:{code})
redis.call('HSET', lobbyKey,
    'code',         inviteCode,
    'host_user_id', userIdentifier,
    'title',        title,
    'max_players',  maxPlayers,
    'is_private',   isPrivate,
    'status',       status
)

-- 3. 참여자 Set에 방장 추가 (lobby:{code}:participants)
redis.call('SADD', participantsKey, userIdentifier)

-- 4. 입장 순서 List에 방장 추가 (lobby:{code}:order)
redis.call('RPUSH', orderKey, userIdentifier)

-- 5. 공개 로비인 경우 전역 공개 목록 Set에 코드 추가 (lobby:public)
-- [isPrivate 값 보장]
-- Java LobbyRepositoryImpl.normalizeIsPrivate()에서 반드시 소문자 "true"/"false"로
-- 정규화하여 전달하므로, 이 비교는 항상 일관되게 동작한다.

if isPrivate == "false" then
    redis.call('SADD', publicListKey, inviteCode)
end

return "OK"