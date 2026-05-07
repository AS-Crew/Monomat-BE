---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 입장 원자적 처리 스크립트
--
-- [책임]
-- 1. 로비 존재 여부 검증
-- 2. participants Set에 userIdentifier 저장
-- 3. order List에 userIdentifier 저장
-- 4. wsSessionId 기준으로 lobbyCode, userIdentifier 매핑 저장
-- 5. userIdentifier 기준 현재 유효 wsSessionId 저장
-- 6. 중복 구독 및 재연결 겹침 상황 처리
--
-- [동일 userIdentifier 다중 세션 정책]
-- 같은 userIdentifier가 같은 로비에 다시 입장하면 최신 wsSessionId를 현재 유효 세션으로 본다.
-- 이전 wsSessionId는 Java에서 stale 세션으로 처리하여 DISCONNECT 시 퇴장 처리하지 않는다.
-- ============================================================================

local lobbyKey              = KEYS[1] -- lobby:{code}
local participantsKey       = KEYS[2] -- lobby:{code}:participants
local orderKey              = KEYS[3] -- lobby:{code}:order
local wsConnectionKey       = KEYS[4] -- ws:connection:{wsSessionId}
local lobbyUserSessionKey   = KEYS[5] -- lobby:{code}:user_session:{userIdentifier}

local userIdentifier        = ARGV[1] -- 사용자 식별자(UUID)
local lobbyCode             = ARGV[2] -- 로비 초대 코드
local connectionTtlMs       = ARGV[3] -- ws:connection TTL(ms)
local userField             = ARGV[4] -- WebSocketHeaders.SESSION_USER_ID
local lobbyField            = ARGV[5] -- WebSocketHeaders.SESSION_LOBBY_CODE
local wsSessionId           = ARGV[6] -- 현재 WebSocket 세션 ID

-- 1. 로비가 존재하지 않으면 입장 상태를 만들지 않는다.
if redis.call('EXISTS', lobbyKey) == 0 then
    return "LOBBY_NOT_FOUND"
end

-- 2. 기존에 같은 userIdentifier로 등록된 현재 유효 wsSessionId를 조회한다.
local previousWsSessionId = redis.call('GET', lobbyUserSessionKey)

-- 3. 참여자 Set에 추가한다.
-- SADD 반환값:
-- - 1: 신규 참여자
-- - 0: 이미 참여 중인 사용자
local added = redis.call('SADD', participantsKey, userIdentifier)

-- 4. 신규 참여자일 때만 order List에 추가한다.
-- 이미 참여 중인 사용자의 재연결/중복 구독은 order를 중복 저장하지 않는다.
if added == 1 then
    redis.call('RPUSH', orderKey, userIdentifier)
end

-- 5. 현재 WebSocket 세션 기준 역추적 정보를 저장한다.
redis.call('HSET', wsConnectionKey,
    userField,  userIdentifier,
    lobbyField, lobbyCode
)
redis.call('PEXPIRE', wsConnectionKey, connectionTtlMs)

-- 6. userIdentifier 기준 현재 유효 세션을 최신 wsSessionId로 갱신한다.
redis.call('SET', lobbyUserSessionKey, wsSessionId, 'PX', connectionTtlMs)

-- 7. 반환값 결정
if added == 1 then
    return "ENTERED"
end

if previousWsSessionId and previousWsSessionId ~= wsSessionId then
    return "SESSION_REPLACED:" .. previousWsSessionId
end

return "ALREADY_JOINED"