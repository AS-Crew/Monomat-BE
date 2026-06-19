---@diagnostic disable: undefined-global

-- ============================================================================
-- 빈 로비 폭파(reaper) 스크립트
--
-- [배경]
-- 로비를 제거하는 정상 경로는 leave_lobby.lua의 SCARD==0 -> DESTROYED 하나뿐이며,
-- 이는 모든 참여자가 1명씩 정상 STOMP DISCONNECT로 제거되는 것에 전적으로 의존한다.
-- 비정상 종료(브라우저 강제 종료, 네트워크 단절, 서버 크래시, ws:connection TTL 만료)로
-- DISCONNECT 이벤트가 누락되거나, 생성 직후 아무도 구독하지 않은 로비는
-- participants Set에 유령으로 남거나 0명인 채로 영구 잔존한다.
--
-- [책임]
-- 단일 로비 코드에 대해 "활성 세션 0" 여부를 원자적으로 판정하고, 죽은 로비를
-- leave_lobby.lua의 DESTROYED 경로와 동일하게 폭파한다.
--
-- [원자성]
-- 생존 판정(SMEMBERS + 로비별 세션 키 확인)과 폭파(DEL + 인덱스 정리)를 한 스크립트
-- 안에서 수행하므로, 스캔과 폭파 사이에 다른 유저가 입장(enter_lobby.lua)하는
-- race condition(TOCTOU)이 발생하지 않는다. 입장이 폭파보다 먼저면 ALIVE로 보호되고,
-- 폭파가 먼저면 입장은 LOBBY_NOT_FOUND로 실패한다.
--
-- [생존 판정 기준]
-- 전역 온라인 상태(user_status)가 아니라, 참여자가 "이 로비에 대해" 유효한 WebSocket
-- 세션을 가지는지로 판정한다. (lobby:{code}:user_session:{id} -> ws:connection 교차 검증)
--
-- [반환값]
-- "REAPED"      : 빈 로비를 폭파했다 (0명 또는 이 로비 유효 세션 보유자 없음).
-- "ALIVE"       : 이 로비에 유효 세션을 가진 참여자가 1명 이상 존재하여 보존했다.
-- "TOO_YOUNG"   : 생성 후 grace 기간이 지나지 않아 보존했다 (구독 대기 중 보호).
-- "STALE_INDEX" : Hash가 이미 없는 stale 인덱스를 정리했다 (self-heal).
-- ============================================================================

local lobbyKey = KEYS[1]                      -- lobby:{code} (Hash)
local participantsKey = KEYS[2]               -- lobby:{code}:participants (Set)
local orderKey = KEYS[3]                      -- lobby:{code}:order (List)
local kickedKey = KEYS[4]                     -- lobby:{code}:kicked (Set)
local readyKey = KEYS[5]                      -- lobby:{code}:ready (Set)
local lobbyAllKey = KEYS[6]                   -- lobby:all (전체 로비 인덱스 Set)
local publicListKey = KEYS[7]                 -- lobby:public (Set)
local publicLatestIndexKey = KEYS[8]          -- lobby:public:latest (ZSET)
local publicMostPlayersIndexKey = KEYS[9]     -- lobby:public:most_players (ZSET)
local publicMostAvailableIndexKey = KEYS[10]  -- lobby:public:most_available (ZSET)

local lobbyCode = ARGV[1]                      -- 대상 로비 코드
local graceMillis = tonumber(ARGV[2])          -- 생성 직후 보호 기간(ms)
local lobbyUserSessionPrefix = ARGV[3]         -- 로비별 현재 세션 키 prefix ("lobby:{code}:user_session:")
local lobbyUserSessionSeqPrefix = ARGV[4]      -- 로비별 세션 sequence 키 prefix ("lobby:{code}:user_session_seq:")
local wsConnectionPrefix = ARGV[5]             -- WebSocket 세션 매핑 Hash 키 prefix ("ws:connection:")
local lobbyField = ARGV[6]                      -- ws:connection Hash의 lobbyCode 필드명

local FIELD_CREATED_AT_EPOCH_MILLIS = 'created_at_epoch_millis'

-- 모든 인덱스(전체/공개)에서 현재 로비를 제거한다.
local function removeAllIndexes()
    redis.call('SREM', lobbyAllKey, lobbyCode)
    redis.call('SREM', publicListKey, lobbyCode)
    redis.call('ZREM', publicLatestIndexKey, lobbyCode)
    redis.call('ZREM', publicMostPlayersIndexKey, lobbyCode)
    redis.call('ZREM', publicMostAvailableIndexKey, lobbyCode)
end

-- 1. Hash가 이미 없으면 stale 인덱스만 정리한다(self-heal).
--    leave/delete 경로에서 SREM이 누락됐더라도 lobby:all이 점진적으로 정합화된다.
if redis.call('EXISTS', lobbyKey) == 0 then
    removeAllIndexes()
    return "STALE_INDEX"
end

-- 2. 생성 후 grace 기간이 지나지 않았으면 보존한다.
--    [버그 방어] 로비는 REST 생성 직후 participants가 0이며, 실제 등록은 WebSocket
--    구독(enter_lobby.lua) 시점에 일어난다. 이 정상 윈도우의 로비를 폭파하면 안 된다.
--    Redis TIME을 단일 시간원으로 사용해 멀티 인스턴스 시계 편차를 배제한다.
local createdAt = tonumber(redis.call('HGET', lobbyKey, FIELD_CREATED_AT_EPOCH_MILLIS))

if createdAt ~= nil and graceMillis ~= nil and graceMillis > 0 then
    local redisTime = redis.call('TIME')
    local nowMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)

    if (nowMillis - createdAt) < graceMillis then
        return "TOO_YOUNG"
    end
end

-- 3. 참여자 중 한 명이라도 "이 로비에 대한 유효 세션"을 가지면 보존한다.
--    [전역 user_status를 쓰지 않는 이유]
--    user_status:{userIdentifier}는 "이 유저가 어딘가 온라인"일 뿐 "이 로비에 연결됨"이 아니다.
--    A 로비에서 유령으로 남은 유저가 B 로비에 접속하면 user_status가 다시 존재하므로
--    A 로비 reaper가 유령을 온라인으로 오판해 ALIVE를 반환하는 버그가 생긴다.
--
--    [로비별 세션 키 기준 판정]
--    enter_lobby.lua가 관리하는 로비별 현재 세션 키 lobby:{code}:user_session:{userIdentifier}로
--    wsSessionId를 얻고, 그 세션의 ws:connection:{wsSessionId} Hash가 실제로 이 로비를
--    가리키는지(lobbyField == lobbyCode)까지 확인한다. 비정상 종료로 세션이 끊기면 이 키들은
--    TTL로 만료되므로 stale 세션은 ALIVE로 오판되지 않는다.
--    단일 노드 Redis(standalone)이므로 멤버 값으로 키를 동적 구성해도 안전하다.
local participants = redis.call('SMEMBERS', participantsKey)

for i = 1, #participants do
    local wsSessionId = redis.call('GET', lobbyUserSessionPrefix .. participants[i])

    if wsSessionId ~= false then
        local wsLobbyCode = redis.call('HGET', wsConnectionPrefix .. wsSessionId, lobbyField)

        if wsLobbyCode == lobbyCode then
            return "ALIVE"
        end
    end
end

-- 4. 0명이거나 전원 오프라인이면 로비를 폭파한다.
--    leave_lobby.lua의 DESTROYED 경로와 동일하게 모든 로비 키와 인덱스를 제거한다.
redis.call('DEL', lobbyKey, participantsKey, orderKey, kickedKey, readyKey)

for i = 1, #participants do
    redis.call('DEL', lobbyUserSessionPrefix .. participants[i], lobbyUserSessionSeqPrefix .. participants[i])
end

removeAllIndexes()

return "REAPED"
