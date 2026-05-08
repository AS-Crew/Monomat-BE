---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 입장 원자적 처리 스크립트
--
-- [책임]
-- 1. 로비 존재 여부 검증
-- 2. 동일 userIdentifier의 세션 sequence 비교
-- 3. participants Set에 userIdentifier 저장
-- 4. order List에 userIdentifier 저장
-- 5. wsSessionId 기준 lobbyCode, userIdentifier 매핑 저장
-- 6. userIdentifier 기준 현재 유효 wsSessionId 저장
-- 7. userIdentifier 기준 현재 유효 sessionSequence 저장
--
-- [동일 userIdentifier 다중 세션 정책]
-- 같은 userIdentifier가 같은 로비에 다시 입장하면 sessionSequence가 더 큰 세션을
-- 현재 유효 세션으로 본다.
--
-- Redis Lua는 원자적으로 실행되지만, 네트워크 지연으로 오래된 SUBSCRIBE 요청이
-- 더 늦게 도착할 수 있다. 이를 막기 위해 sessionSequence를 비교하여
-- 더 오래된 세션은 user_session을 덮어쓰지 않고 STALE_SESSION으로 거부한다.
--
-- 단, 같은 wsSessionId가 다시 들어온 경우에는 동일 세션의 재구독/중복 구독으로 보고
-- STALE_SESSION으로 거부하지 않고 TTL만 갱신한다.
-- ============================================================================

local lobbyKey                  = KEYS[1] -- lobby:{code}
local participantsKey           = KEYS[2] -- lobby:{code}:participants
local orderKey                  = KEYS[3] -- lobby:{code}:order
local wsConnectionKey           = KEYS[4] -- ws:connection:{wsSessionId}
local lobbyUserSessionKey       = KEYS[5] -- lobby:{code}:user_session:{userIdentifier}
local lobbyUserSessionSeqKey    = KEYS[6] -- lobby:{code}:user_session_seq:{userIdentifier}

local userIdentifier            = ARGV[1] -- 사용자 식별자(UUID)
local lobbyCode                 = ARGV[2] -- 로비 초대 코드
local connectionTtlMs           = ARGV[3] -- ws/user_session TTL(ms)
local userField                 = ARGV[4] -- WebSocketHeaders.SESSION_USER_ID
local lobbyField                = ARGV[5] -- WebSocketHeaders.SESSION_LOBBY_CODE
local wsSessionId               = ARGV[6] -- 현재 WebSocket 세션 ID
local sessionSequence           = tonumber(ARGV[7]) -- 현재 WebSocket 세션 sequence

-- 1. 로비가 존재하지 않으면 입장 상태를 만들지 않는다.
if redis.call('EXISTS', lobbyKey) == 0 then
    return "LOBBY_NOT_FOUND"
end

-- 2. sessionSequence가 숫자가 아니면 잘못된 서버 상태로 본다.
-- sessionSequence는 CONNECT 단계에서 Redis INCR로 발급되고 Java에서 검증 후 전달된다.
-- 따라서 nil 또는 숫자 변환 실패는 재시도 가능한 사용자 입력 오류가 아니라
-- Java-Lua 계약 위반에 가까우므로 입장을 허용하지 않는다.
if sessionSequence == nil then
    return "INVALID_SEQUENCE"
end

-- 3. 기존 현재 유효 세션과 sequence를 조회한다.
local previousWsSessionId = redis.call('GET', lobbyUserSessionKey)
local previousSequence = redis.call('GET', lobbyUserSessionSeqKey)

-- 4. 이미 더 최신 세션이 존재하면 현재 요청은 stale로 간주한다.
-- 다만 동일 wsSessionId가 다시 들어온 경우에는 같은 세션의 재구독/중복 구독으로 보고
-- stale 거부 대신 TTL을 갱신한다.
if previousSequence ~= false and tonumber(previousSequence) > sessionSequence then
    if previousWsSessionId ~= false and previousWsSessionId == wsSessionId then
        redis.call('HSET', wsConnectionKey,
            userField,  userIdentifier,
            lobbyField, lobbyCode
        )
        redis.call('PEXPIRE', wsConnectionKey, connectionTtlMs)
        redis.call('PEXPIRE', lobbyUserSessionKey, connectionTtlMs)
        redis.call('PEXPIRE', lobbyUserSessionSeqKey, connectionTtlMs)

        return "ALREADY_JOINED"
    end

    local currentWsSessionId = previousWsSessionId

    if currentWsSessionId == false then
        currentWsSessionId = ""
    end

    return "STALE_SESSION:" .. currentWsSessionId
end

-- 5. 참여자 Set에 추가한다.
-- SADD 반환값:
-- - 1: 신규 참여자
-- - 0: 이미 참여 중인 사용자
local added = redis.call('SADD', participantsKey, userIdentifier)

-- 6. 신규 참여자일 때만 order List에 추가한다.
-- 이미 참여 중인 사용자의 재연결/중복 구독은 order를 중복 저장하지 않는다.
if added == 1 then
    redis.call('RPUSH', orderKey, userIdentifier)
end

-- 7. 현재 WebSocket 세션 기준 역추적 정보를 저장한다.
redis.call('HSET', wsConnectionKey,
    userField,  userIdentifier,
    lobbyField, lobbyCode
)
redis.call('PEXPIRE', wsConnectionKey, connectionTtlMs)

-- 8. userIdentifier 기준 현재 유효 세션을 최신 wsSessionId로 갱신한다.
redis.call('SET', lobbyUserSessionKey, wsSessionId, 'PX', connectionTtlMs)

-- 9. userIdentifier 기준 현재 유효 세션 sequence도 함께 저장한다.
redis.call('SET', lobbyUserSessionSeqKey, tostring(sessionSequence), 'PX', connectionTtlMs)

-- 10. 반환값 결정
if added == 1 then
    return "ENTERED"
end

if previousWsSessionId ~= false and previousWsSessionId ~= wsSessionId then
    return "SESSION_REPLACED:" .. previousWsSessionId
end

return "ALREADY_JOINED"