---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 유저 강퇴 원자적 처리 스크립트
--
-- [책임]
-- 1. 로비 존재 여부 검증
-- 2. 요청자가 현재 방장인지 검증
-- 3. 강퇴 대상이 로비 참여자인지 검증
-- 4. 방장 자기 자신 강퇴 방지
-- 5. participants Set에서 강퇴 대상 제거
-- 6. order List에서 강퇴 대상 제거
-- 7. 강퇴 대상의 현재 유효 WebSocket 세션 매핑 조회
-- 8. 강퇴 대상의 lobby user_session / user_session_seq 키 삭제
--
-- [주의]
-- 실제 WebSocket 물리 close는 이 스크립트에서 수행할 수 없다.
-- 이 스크립트는 Redis 상태를 원자적으로 변경하고,
-- Java 레이어가 반환된 wsSessionId를 이용해 강퇴 알림을 전송하도록 한다.
-- ============================================================================

local lobbyKey                  = KEYS[1] -- lobby:{code}
local participantsKey           = KEYS[2] -- lobby:{code}:participants
local orderKey                  = KEYS[3] -- lobby:{code}:order
local kickedKey                 = KEYS[4] -- lobby:{code}:kicked
local targetLobbyUserSessionKey = KEYS[5] -- lobby:{code}:user_session:{targetUserIdentifier}
local targetLobbyUserSeqKey     = KEYS[6] -- lobby:{code}:user_session_seq:{targetUserIdentifier}

local requesterIdentifier       = ARGV[1] -- 강퇴 요청자 식별자
local targetUserIdentifier      = ARGV[2] -- 강퇴 대상 식별자

local FIELD_CURRENT_PLAYERS = 'current_players'

-- 1. 로비 존재 여부 검증
if redis.call('EXISTS', lobbyKey) == 0 then
    return "LOBBY_NOT_FOUND"
end

-- 2. 방장 권한 검증
local currentHost = redis.call('HGET', lobbyKey, 'host_user_id')

if currentHost == false or currentHost == nil or currentHost == '' then
    return "HOST_NOT_FOUND"
end

if currentHost ~= requesterIdentifier then
    return "FORBIDDEN"
end

-- 3. 자기 자신 강퇴 방지
if requesterIdentifier == targetUserIdentifier then
    return "CANNOT_KICK_SELF"
end

-- 4. 강퇴 대상 참여 여부 검증
local isParticipant = redis.call('SISMEMBER', participantsKey, targetUserIdentifier)

if isParticipant == 0 then
    return "TARGET_NOT_PARTICIPANT"
end

-- 5. 강퇴 대상의 현재 유효 WebSocket 세션 ID 조회
local targetWsSessionId = redis.call('GET', targetLobbyUserSessionKey)

if targetWsSessionId == false or targetWsSessionId == nil then
    targetWsSessionId = ""
end

-- 6. participants / order에서 강퇴 대상 제거
redis.call('SREM', participantsKey, targetUserIdentifier)
redis.call('LREM', orderKey, 0, targetUserIdentifier)

-- participants Set을 기준으로 현재 인원 캐시를 재계산한다.
-- 강퇴는 participants 변경과 같은 원자 단위에서 처리되므로,
-- current_players 캐시도 같은 Lua 스크립트 내부에서만 갱신한다.
local currentPlayers = redis.call('SCARD', participantsKey)
redis.call('HSET', lobbyKey, FIELD_CURRENT_PLAYERS, tostring(currentPlayers))

-- 7. 강퇴 대상 재입장 차단 등록
redis.call('SADD', kickedKey, targetUserIdentifier)

-- 8. 로비 내 대상 유저 세션 매핑 제거
redis.call('DEL', targetLobbyUserSessionKey, targetLobbyUserSeqKey)

-- 9. 결과 반환
-- Java 레이어에서 targetWsSessionId를 파싱해 ws:connection:{sessionId} 정리 및 알림 처리에 사용한다.
return "KICKED:" .. targetWsSessionId