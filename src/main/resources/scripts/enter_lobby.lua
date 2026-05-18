---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 입장 원자적 처리 스크립트
--
-- [책임]
-- 1. 로비 존재 여부 검증
-- 2. 동일 userIdentifier의 세션 sequence 비교
-- 3. 최대 인원 초과 여부 검증 (Race Condition 방어용 최종 검증)
-- 4. participants Set에 userIdentifier 저장
-- 5. order List에 userIdentifier 저장
-- 6. wsSessionId 기준 lobbyCode, userIdentifier 매핑 저장
-- 7. userIdentifier 기준 현재 유효 wsSessionId 저장
-- 8. userIdentifier 기준 현재 유효 sessionSequence 저장
--
-- [인원 검증 설계 의도]
-- REST API (/api/lobbies/join)의 인원 체크는 UX용 사전 검증이고,
-- 이 스크립트의 인원 체크는 Race Condition 방어용 원자적 최종 검증이다.
-- 두 레이어가 역할이 다르므로 둘 다 존재해야 한다.
--
-- [이미 참여 중인 유저 처리]
-- 재접속 (ALREADY_JOINED, SESSION_REPLACED)인 경우에는 인원 초과 검증을 건너뛴다.
-- 이미 participants Set에 존재하므로 SCARD가 증가하지 않기 때문
--
-- [동일 userIdentifier 다중 세션 정책]
-- 같은 userIdentifier가 같은 로비에 다시 입장하면 sessionSequence가 더 큰 세션을 현재 유효 세션으로 본다.
-- ============================================================================

local lobbyKey                  = KEYS[1] -- lobby:{code}
local participantsKey           = KEYS[2] -- lobby:{code}:participants
local orderKey                  = KEYS[3] -- lobby:{code}:order
local kickedKey                 = KEYS[4] -- lobby:{code}:kicked
local wsConnectionKey           = KEYS[5] -- ws:connection:{wsSessionId}
local lobbyUserSessionKey       = KEYS[6] -- lobby:{code}:user_session:{userIdentifier}
local lobbyUserSessionSeqKey    = KEYS[7] -- lobby:{code}:user_session_seq:{userIdentifier}
local publicMostPlayersIndexKey     = KEYS[8] -- lobby:public:most_players
local publicMostAvailableIndexKey   = KEYS[9] -- lobby:public:most_available

local userIdentifier            = ARGV[1] -- 사용자 식별자(UUID)
local lobbyCode                 = ARGV[2] -- 로비 초대 코드
local connectionTtlMs           = ARGV[3] -- ws/user_session TTL(ms)
local userField                 = ARGV[4] -- WebSocketHeaders.SESSION_USER_ID
local lobbyField                = ARGV[5] -- WebSocketHeaders.SESSION_LOBBY_CODE
local wsSessionId               = ARGV[6] -- 현재 WebSocket 세션 ID
local sessionSequence           = tonumber(ARGV[7]) -- 현재 WebSocket 세션 sequence

-- Redis Hash 필드명
local FIELD_CURRENT_PLAYERS = 'current_players'
local FIELD_MAX_PLAYERS = 'max_players'
local FIELD_IS_PRIVATE = 'is_private'

-- 1. 로비가 존재하지 않으면 입장 상태를 만들지 않는다.
if redis.call('EXISTS', lobbyKey) == 0 then
    return "LOBBY_NOT_FOUND"
end

-- 2. WAITING 상태의 로비만 입장을 허용한다.
-- REST join API는 UX용 사전 검증이고, 실제 입장 확정은 이 Lua에서 수행되므로
-- PLAYING / FINISHED 상태 로비는 여기서 최종 차단해야 한다.
local lobbyStatus = redis.call('HGET', lobbyKey, 'status')

if lobbyStatus ~= 'WAITING' then
    return "LOBBY_NOT_WAITING"
end

-- 3. 강퇴된 유저는 같은 로비에 재입장할 수 없다.
if redis.call('SISMEMBER', kickedKey, userIdentifier) == 1 then
    return "KICKED_USER"
end

-- 4. sessionSequence가 숫자가 아니면 잘못된 서버 상태로 본다.
if sessionSequence == nil then
    return "INVALID_SEQUENCE"
end

-- 5. 기존 현재 유효 세션과 sequence를 조회한다.
local previousWsSessionId = redis.call('GET', lobbyUserSessionKey)
local previousSequence = redis.call('GET', lobbyUserSessionSeqKey)

-- 6. 이미 더 최신 세션이 존재하면 현재 요청은 stale로 간주한다.
if previousSequence ~= false and tonumber(previousSequence) > sessionSequence then
    if previousWsSessionId ~= false and previousWsSessionId == wsSessionId then
        redis.call('HSET', wsConnectionKey,
            userField,  userIdentifier,
            lobbyField, lobbyCode
        )
        redis.call('PEXPIRE', wsConnectionKey,       connectionTtlMs)
        redis.call('PEXPIRE', lobbyUserSessionKey,    connectionTtlMs)
        redis.call('PEXPIRE', lobbyUserSessionSeqKey, connectionTtlMs)
        return "ALREADY_JOINED"
    end

    local currentWsSessionId = previousWsSessionId ~= false and previousWsSessionId or ""
    return "STALE_SESSION:" .. currentWsSessionId
end


-- 7. 이미 참여 중인 유저인지 먼저 확인한다.
--    재접속(SESSION_REPLACED, ALREADY_JOINED)인 경우에는 인원 초과 검증을 건너뛴다.
--    participants Set에 이미 존재하므로 SADD 결과가 0이 되어 SCARD가 증가하지 않기 때문이다.
local alreadyInLobby = redis.call('SISMEMBER', participantsKey, userIdentifier)

-- 8. 신규 입장자에 한해서만 최대 인원 초과를 검증한다.
--    [Race Condition 방어]
--    REST API의 인원 검증과 달리, SADD 직전에 검증하므로 원자적으로 처리된다.
if alreadyInLobby == 0 then
    local currentCount = redis.call('SCARD', participantsKey)
    local maxPlayers   = tonumber(redis.call('HGET', lobbyKey, 'max_players'))

    if maxPlayers == nil or maxPlayers <= 0 then
        return "INVALID_LOBBY_CAPACITY"
    end

    if currentCount >= maxPlayers then
        return "FULL"
    end
end

-- 9. 참여자 Set에 추가한다.
local added = redis.call('SADD', participantsKey, userIdentifier)

-- 10. 신규 참여자일 때만 order List와 current_players를 갱신한다.
if added == 1 then
    redis.call('RPUSH', orderKey, userIdentifier)

    -- participants Set이 현재 인원의 단일 진실의 원천이다.
    local currentPlayers = redis.call('SCARD', participantsKey)
    redis.call('HSET', lobbyKey, FIELD_CURRENT_PLAYERS, tostring(currentPlayers))

    -- 공개 로비인 경우에만 공개 목록 정렬 인덱스를 갱신한다.
    -- 비공개 로비에 대해 ZADD를 수행하면 공개 인덱스에 노출되는 심각한 버그가 된다.
    local isPrivate = redis.call('HGET', lobbyKey, FIELD_IS_PRIVATE)

    if isPrivate == 'false' then
        local maxPlayersForIndex = tonumber(redis.call('HGET', lobbyKey, FIELD_MAX_PLAYERS))

        if maxPlayersForIndex ~= nil and maxPlayersForIndex > 0 then
            local availableSeats = maxPlayersForIndex - currentPlayers

            if availableSeats < 0 then
                availableSeats = 0
            end

            redis.call('ZADD', publicMostPlayersIndexKey, currentPlayers, lobbyCode)
            redis.call('ZADD', publicMostAvailableIndexKey, availableSeats, lobbyCode)
        end
    end
end

-- 11. 현재 WebSocket 세션 기준 역추적 정보를 저장한다.
redis.call('HSET', wsConnectionKey,
    userField,  userIdentifier,
    lobbyField, lobbyCode
)
redis.call('PEXPIRE', wsConnectionKey, connectionTtlMs)

-- 12. userIdentifier 기준 현재 유효 세션을 최신 wsSessionId로 갱신한다.
redis.call('SET', lobbyUserSessionKey,    wsSessionId,                 'PX', connectionTtlMs)
redis.call('SET', lobbyUserSessionSeqKey, tostring(sessionSequence),  'PX', connectionTtlMs)

-- 13. 반환값 결정
if added == 1 then
    return "ENTERED"
end

if previousWsSessionId ~= false and previousWsSessionId ~= wsSessionId then
    return "SESSION_REPLACED:" .. previousWsSessionId
end

return "ALREADY_JOINED"
