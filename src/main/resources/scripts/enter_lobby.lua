---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 입장 원자적 처리 스크립트
--
-- [책임]
-- 1. 로비 존재 여부 검증
-- 2. participants Set에 userIdentifier 저장
-- 3. order List에 userIdentifier 저장
-- 4. wsSessionId 기준으로 lobbyCode, userIdentifier 매핑 저장
-- 5. 중복 구독 시 participants/order 중복 저장 방지
--
-- [원자성]
-- Redis Lua 스크립트는 싱글 스레드로 실행되므로,
-- 아래의 입장 상태 변경 작업은 중간에 다른 Redis 명령이 끼어들 수 없다.
--
-- [중복 구독 처리]
-- SADD 결과가 1이면 신규 입장자
-- SADD 결과가 0이면 이미 participants에 존재하는 사용자이므로,
-- order List에는 다시 넣지 않는다.
-- 단, WebSocket 세션은 새로 열렸을 수 있으므로 ws:connection 매핑은 항상 갱신한다.
-- ============================================================================

local lobbyKey        = KEYS[1] -- lobby:{code}
local participantsKey = KEYS[2] -- lobby:{code}:participants
local orderKey        = KEYS[3] -- lobby:{code}:order
local wsConnectionKey = KEYS[4] -- ws:connection:{wsSessionId}

local userIdentifier  = ARGV[1] -- 사용자 식별자(UUID)
local lobbyCode       = ARGV[2] -- 로비 초대 코드
local connectionTtlMs = ARGV[3] -- ws:connection TTL(ms)
local userField       = ARGV[4] -- WebSocketHeaders.SESSION_USER_ID
local lobbyField      = ARGV[5] -- WebSocketHeaders.SESSION_LOBBY_CODE

-- 1. 로비가 존재하지 않으면 입장 상태를 만들지 않는다.
-- 존재하지 않는 로비에 participants/order가 생기는 고스트 데이터 방지용
if redis.call('EXISTS', lobbyKey) == 0 then
    return "LOBBY_NOT_FOUND"
end

-- 2. 참여자 Set에 추가
-- SADD 반환값:
-- - 1: 새로 추가됨
-- - 0: 이미 존재함
local added = redis.call('SADD', participantsKey, userIdentifier)

-- 3. 신규 입장자일 때만 order List에 추가한다.
-- 중복 구독 또는 새로고침으로 같은 사용자가 다시 구독해도 방장 위임 순서가 중복되지 않는다.
if added == 1 then
    redis.call('RPUSH', orderKey, userIdentifier)
end

-- 4. WebSocket 세션 ID 기준 역추적 정보를 저장한다.
-- DISCONNECT 이벤트에서 wsSessionId만으로 lobbyCode를 찾기 위해 필요하다.
redis.call('HSET', wsConnectionKey,
    userField,  userIdentifier,
    lobbyField, lobbyCode
)

-- 5. 비정상 종료 또는 서버 장애 시 좀비 ws:connection 키가 남지 않도록 TTL을 설정한다.
redis.call('PEXPIRE', wsConnectionKey, connectionTtlMs)

if added == 1 then
    return "ENTERED"
end

return "ALREADY_JOINED"