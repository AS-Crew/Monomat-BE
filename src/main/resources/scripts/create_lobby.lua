---@diagnostic disable: undefined-global

-- ============================================================================
-- 로비 생성 원자적 처리 스크립트
--
-- SETNX 선점 + Hash 저장 + 공개 로비 인덱스 등록을 단일 트랜잭션으로 처리하여
-- 중간 실패 시 부분 데이터가 남는 문제를 방지한다.
-- Redis는 Lua 스크립트를 싱글 스레드로 실행하므로
-- 스크립트 실행 중에는 다른 명령이 끼어들 수 없다.
--
-- [책임 분리 원칙]
-- create_lobby.lua : 로비 메타 정보(Hash) 저장 + 공개 목록/정렬 인덱스 등록만 담당
-- processLobbyEnter(): 입장 처리(participants, order, 세션 매핑, ENTER 브로드캐스트) 담당
-- ============================================================================

local lockKey               = KEYS[1]   -- lobby:code:lock:{code}
local lobbyKey              = KEYS[2]   -- lobby:{code}
local publicListKey         = KEYS[3]   -- lobby:public
local publicLatestIndexKey  = KEYS[4]   -- lobby:public:latest

local userIdentifier  = ARGV[1]   -- 방장 식별자 (SETNX 선점자)
local lockTtlMs       = ARGV[2]   -- 락 TTL (밀리초)
local inviteCode      = ARGV[3]   -- 초대 코드
local title           = ARGV[4]   -- 로비 제목
local maxPlayers      = ARGV[5]   -- 최대 인원
local isPrivate       = ARGV[6]   -- "true" | "false"
local status          = ARGV[7]   -- "WAITING"

local mapId           = ARGV[8]   -- 선택된 맵 ID. 미선택 시 ""
local mapTitle        = ARGV[9]   -- 선택된 맵 제목. 미선택 시 ""
local mapCategory     = ARGV[10]  -- 선택된 맵 카테고리. 미선택 시 ""

-- Redis Hash 필드명은 스크립트 상단에서 중앙 관리합니다.
local FIELD_CODE                    = 'code'
local FIELD_HOST_USER_ID            = 'host_user_id'
local FIELD_TITLE                   = 'title'
local FIELD_MAX_PLAYERS             = 'max_players'
local FIELD_CURRENT_PLAYERS         = 'current_players'
local FIELD_IS_PRIVATE              = 'is_private'
local FIELD_STATUS                  = 'status'
local FIELD_STATUS                  = 'status'
local FIELD_MAP_ID                  = 'map_id'
local FIELD_MAP_TITLE               = 'map_title'
local FIELD_MAP_CATEGORY            = 'map_category'
local FIELD_CREATED_AT_EPOCH_MILLIS = 'created_at_epoch_millis'

-- Redis 서버 기준 현재 시각을 epoch milliseconds로 계산한다.
--
-- [Java 서버 시간이 아니라 Redis TIME을 사용하는 이유]
-- Monomat-BE가 멀티 인스턴스로 운영될 경우 각 애플리케이션 서버의 시간이
-- 미세하게 다를 수 있다. latest 정렬 기준을 Java Instant.now()에 의존하면
-- 서버 간 NTP drift로 인해 생성 순서가 흔들릴 수 있다.
--
-- Redis TIME은 Redis 서버 기준 단일 시간원이므로,
-- Redis에 저장되는 로비 생성 시각 정렬 기준을 일관되게 유지할 수 있다.
local redisTime = redis.call('TIME')
local createdAtEpochMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)

-- 1. SETNX 선점 시도
--    이미 동일한 코드가 선점되어 있으면 LOCK_FAILED 반환
local acquired = redis.call('SET', lockKey, userIdentifier, 'NX', 'PX', lockTtlMs)

if acquired == false then
    return "LOCK_FAILED"
end

-- 2. 로비 메타 정보 Hash 저장 (lobby:{code})
--
-- [created_at_epoch_millis 저장 이유]
-- lobby:public은 Redis Set이므로 생성 순서를 보장하지 않는다.
-- 공개 로비 목록에서 latest 정렬을 제공하려면 생성 시각을 별도 필드로 저장해야 한다.
redis.call('HSET', lobbyKey,
    FIELD_CODE,                    inviteCode,
    FIELD_HOST_USER_ID,            userIdentifier,
    FIELD_TITLE,                   title,
    FIELD_MAX_PLAYERS,             maxPlayers,
    FIELD_CURRENT_PLAYERS,         '0',
    FIELD_IS_PRIVATE,              isPrivate,
    FIELD_STATUS,                  status,
    FIELD_CREATED_AT_EPOCH_MILLIS, tostring(createdAtEpochMillis)
)

-- 3. 맵이 선택된 경우에만 맵 메타 정보 저장
if mapId ~= "" then
    redis.call('HSET', lobbyKey,
        FIELD_MAP_ID,       mapId,
        FIELD_MAP_TITLE,    mapTitle,
        FIELD_MAP_CATEGORY, mapCategory
    )
end

-- 4. 공개 로비인 경우 전역 공개 목록 Set과 최신순 ZSET 인덱스에 코드 추가
--
-- [isPrivate 값 보장]
-- Java LobbyLuaScriptExecutor.normalizeIsPrivate()에서 반드시 소문자 "true"/"false"로
-- 정규화하여 전달하므로, 이 비교는 항상 일관되게 동작한다.
--
-- [ZSET 인덱스]
-- lobby:public:latest는 최신순 페이징 조회를 위한 정렬 인덱스다.
-- score는 Redis TIME 기준 createdAtEpochMillis를 사용한다.
-- 이 값은 lobby:{code}.created_at_epoch_millis와 동일해야 한다.
if isPrivate == "false" then
    redis.call('SADD', publicListKey, inviteCode)
    redis.call('ZADD', publicLatestIndexKey, createdAtEpochMillis, inviteCode)
end

return "OK"