/*
 * Redis에서 사용하는 키 패턴을 중앙에서 관리하는 상수 클래스.
 *
 * [네이밍 규칙]
 * - PREFIX  : 키의 접두사 (단독으로 사용하지 않음, private)
 * - FIELD_* : Redis Hash 내부 필드명 상수 (public)
 * - 정적 메서드 : 동적 파라미터가 필요한 키를 생성하는 팩토리 메서드
 *
 * [사용 예시]
 * RedisKeys.lobbyKey("ABC123")             → "lobby:ABC123"
 * RedisKeys.lobbyParticipantsKey("ABC123") → "lobby:ABC123:participants"
 * RedisKeys.FIELD_HOST_USER_ID             → "host_user_id"
 */
package io.github.ascrew.monomatbe.global.constant;

public final class RedisKeys {

    // 인스턴스화 방지
    private RedisKeys() {}

    // =========================================================
    // Key Prefix 상수 (private — 팩토리 메서드를 통해서만 사용)
    // =========================================================

    /** 로비 메타 정보 Hash 키 접두사 */
    private static final String LOBBY_PREFIX = "lobby:";

    /** 사용자 온라인 상태 키 접두사 */
    private static final String USER_STATUS_PREFIX = "user_status:";

    /** 로비별 참여자 Set 키 접미사 */
    private static final String PARTICIPANTS_SUFFIX = ":participants";

    /** 로비별 입장 순서 List 키 접미사 */
    private static final String ORDER_SUFFIX = ":order";

    /** 로비별 강퇴 유저 Set 키 접미사 */
    private static final String KICKED_SUFFIX = ":kicked";

    /** 로비별 준비 완료 유저 Set 키 접미사 */
    private static final String READY_SUFFIX = ":ready";

    /** 로비 내 사용자별 현재 유효 WebSocket 세션 키 접미사 */
    private static final String USER_SESSION_SUFFIX = ":user_session:";

    /** 로비 내 사용자별 현재 유효 WebSocket 세션 sequence 키 접미사 */
    private static final String USER_SESSION_SEQUENCE_SUFFIX = ":user_session_seq:";

    /** WebSocket 세션 매핑 Hash 키 접두사 */
    private static final String WS_CONNECTION_PREFIX = "ws:connection:";

    /** 게스트 세션 저장 키 접두사 */
    private static final String GUEST_SESSION_PREFIX = "auth:guest:session:";

    /** Refresh Token 저장 키 접두사 */
    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";

    /** 초대 코드 중복 방지 SETNX 락 키 접두사 */
    private static final String LOBBY_CODE_LOCK_PREFIX = "lobby:code:lock:";

    /** 공개 맵 목록 캐시 키 */
    private static final String MAP_PUBLIC_LIST_PREFIX = "map:public:list";

    /** 공개 맵 목록 캐시 버전 키 */
    private static final String MAP_PUBLIC_LIST_VERSION = "map:public:list:version";

    /** 공개 맵 단건 캐시 키 접두사 */
    private static final String MAP_PUBLIC_DETAIL_PREFIX = "map:public:";

    /** YouTube oEmbed 성공 캐시 키 접두사 */
    private static final String YOUTUBE_OEMBED_SUCCESS_PREFIX = "youtube:oembed:success:";

    /** YouTube oEmbed 실패 캐시 키 접두사 */
    private static final String YOUTUBE_OEMBED_FAILURE_PREFIX = "youtube:oembed:failure:";

    // =========================================================
    // Redis Hash 필드 키 상수 (auth:guest:session:{token} Hash 내부 필드명)
    // =========================================================

    /** 게스트 세션 Hash의 사용자 DB PK 필드 */
    public static final String FIELD_GUEST_USER_ID = "userId";

    /** 게스트 세션 Hash의 닉네임 필드 */
    public static final String FIELD_GUEST_USERNAME = "username";

    /** 게스트 세션 Hash의 사용자 유형 필드 */
    public static final String FIELD_GUEST_USER_TYPE = "userType";

    // [삭제] USER_ROOM_PREFIX 및 userRoomKey() 제거
    // lobby:{code}:participants가 단일 진실의 원천으로 통일되었으므로
    // user_room:{lobbyCode} 관련 상수는 더 이상 필요하지 않습니다.

    // =========================================================
    // 전역 단일 키 상수
    // =========================================================

    /** 공개 로비 코드 목록을 담는 전역 Set 키 */
    public static final String LOBBY_PUBLIC = "lobby:public";

    /**
     * WebSocket 세션 sequence 발급용 전역 키.
     *
     * 동일 userIdentifier의 재접속이 거의 동시에 발생할 때,
     * 오래된 세션의 늦은 SUBSCRIBE가 최신 세션을 덮어쓰지 않도록
     * Redis INCR 기반 단조 증가 sequence를 발급하는 데 사용합니다.
     */
    public static final String WS_SESSION_SEQUENCE = "ws:session:sequence";

    // =========================================================
    // Redis Hash 필드 키 상수 (lobby:{code} Hash 내부 필드명)
    //
    // [추가 이유]
    // LobbyRepositoryImpl에서 "host_user_id", "title" 등의 문자열 리터럴을
    // 직접 사용하면 오타 발생 시 런타임에서야 발견됩니다.
    // 상수로 통일하여 컴파일 타임 오타 검출 및 저장/조회 필드명 일관성을 보장합니다.
    // =========================================================

    /** 로비 Hash의 초대 코드 필드 */
    public static final String FIELD_CODE = "code";

    /**
     * 로비 Hash의 방장 사용자 ID 필드.
     *
     * [Lua 스크립트 동기화 필요]
     * leave_lobby.lua에서 이 필드명을 문자열 리터럴로 직접 사용합니다.
     * Lua 스크립트는 Java 상수를 참조할 수 없는 구조이므로,
     * 이 값을 변경할 경우 leave_lobby.lua의 'host_user_id'도 반드시 함께 수정해야 합니다.
     *   - HGET lobbyKey, 'host_user_id'
     *   - HSET lobbyKey, 'host_user_id', nextHost
     */
    public static final String FIELD_HOST_USER_ID = "host_user_id";

    /** 로비 Hash의 로비 제목 필드 */
    public static final String FIELD_TITLE = "title";

    /** 로비 Hash의 선택된 맵 ID 필드 (null 가능 — 맵 미선택 상태) */
    public static final String FIELD_MAP_ID = "map_id";

    /** 로비 Hash의 선택된 맵 제목 필드 (맵 미선택 시 필드가 없을 수 있음) */
    public static final String FIELD_MAP_TITLE = "map_title";

    /** 로비 Hash의 선택된 맵 카테고리 필드 */
    public static final String FIELD_MAP_CATEGORY = "map_category";

    /** 로비 Hash의 최대 참여 인원 필드 */
    public static final String FIELD_MAX_PLAYERS = "max_players";

    /**
     * 로비 Hash의 공개/비공개 여부 필드.
     * 저장 값: "true" (비공개) / "false" (공개)
     */
    public static final String FIELD_IS_PRIVATE = "is_private";

    /** 로비 Hash의 상태 필드. 저장 값: "WAITING" / "PLAYING" */
    public static final String FIELD_STATUS = "status";

    // =========================================================
    // 동적 키 생성 팩토리 메서드
    // =========================================================

    /**
     * 로비 메타 정보 Hash 키를 반환합니다.
     * 저장 구조: Hash { code, host_user_id, title, map_id, max_players, is_private, status }
     *
     * @param code 로비 초대 코드
     * @return "lobby:{code}"
     */
    public static String lobbyKey(String code) {
        return LOBBY_PREFIX + code;
    }

    /**
     * 로비 참여자 Set 키를 반환합니다.
     * 저장 구조: Set { userId1, userId2, ... }
     *
     * [단일 진실의 원천]
     * 기존에 user_room:{lobbyCode}와 이중 관리되던 문제를 해결하고
     * 이 키를 참여자 관리의 단일 진실의 원천으로 사용합니다.
     * Lua 스크립트(leave_lobby.lua)의 퇴장 처리와 Java 레벨의 입장 처리 모두
     * 이 키를 일관되게 사용합니다.
     *
     * @param code 로비 초대 코드
     * @return "lobby:{code}:participants"
     */
    public static String lobbyParticipantsKey(String code) {
        return LOBBY_PREFIX + code + PARTICIPANTS_SUFFIX;
    }

    /**
     * 로비 입장 순서 List 키를 반환합니다.
     * 저장 구조: List [ 첫 번째 입장 userId, 두 번째 입장 userId, ... ]
     * 방장 위임 시 LINDEX 0으로 다음 방장을 선정하는 데 사용됩니다.
     *
     * @param code 로비 초대 코드
     * @return "lobby:{code}:order"
     */
    public static String lobbyOrderKey(String code) {
        return LOBBY_PREFIX + code + ORDER_SUFFIX;
    }

    public static String lobbyKickedKey(String code) { return LOBBY_PREFIX + code + KICKED_SUFFIX; }

    /**
     * 로비별 준비 완료 유저 Set 키를 반환한다.
     * 정책 : 방장은 준비 대상에서 제외하고, 일반 참여자만 ready 상태에 포함한다.
     * 저장 구조:
     * - Key   : lobby:{code}:ready
     * - Type  : Set
     * - Value : 준비 완료 상태인 userIdentifier 목록
     * @param code 로비 초대 코드
     * @return "lobby:{code}:ready"
     */
    public static String lobbyReadyKey(String code) {
        return LOBBY_PREFIX + code + READY_SUFFIX;
    }

    /**
     * 게임 시작 상태 동기화 실패 재처리 큐.
     *
     * 저장 구조:
     * - Key  : lobby:start:reconciliation
     * - Type : List
     * - Value: "lobbyCode|reason"
     */
    public static final String LOBBY_START_RECONCILIATION_QUEUE =
            "lobby:start:reconciliation";

    /** 게임 시작 상태 재처리 큐 적재 횟수 metric counter */
    public static final String METRIC_LOBBY_START_RECONCILIATION_ENQUEUED =
            "metric:lobby:start:reconciliation:enqueued";

    /** 게임 시작 상태 재처리 성공 횟수 metric counter */
    public static final String METRIC_LOBBY_START_RECONCILIATION_SUCCESS =
            "metric:lobby:start:reconciliation:success";

    /** 게임 시작 상태 재처리 실패 횟수 metric counter */
    public static final String METRIC_LOBBY_START_RECONCILIATION_FAILED =
            "metric:lobby:start:reconciliation:failed";

    /** start_lobby.lua 알 수 없는 반환값 발생 횟수 */
    public static final String METRIC_START_LOBBY_UNKNOWN_RESULT =
            "metric:lobby:start:unknown-result";

    /** 게임 시작 전 stale ready 데이터 정리 횟수 */
    public static final String METRIC_LOBBY_READY_STALE_CLEANUP =
            "metric:lobby:ready:stale-cleanup";

    /** 게임 시작 실패 시 ready/participants 정합성 진단 발생 횟수 */
    public static final String METRIC_LOBBY_READY_CONSISTENCY_FAILURE =
            "metric:lobby:ready:consistency-failure";

    /**
     * 사용자 온라인 상태 키를 반환합니다.
     * 저장 구조: String "ONLINE"
     *
     * @param userIdentifier 사용자 식별자 (게스트 UUID 또는 회원 ID)
     * @return "user_status:{userIdentifier}"
     */
    public static String userStatusKey(String userIdentifier) {
        return USER_STATUS_PREFIX + userIdentifier;
    }

    /**
     * 사용자 온라인 상태를 구성하는 WebSocket 세션 Set 키를 반환한다.
     *
     * 저장 구조:
     * - Key   : user_status:{userIdentifier}:sessions
     * - Type  : Set
     * - Value : wsSessionId 목록
     *
     * [사용 목적]
     * 동일 userIdentifier가 여러 WebSocket 세션을 가질 수 있으므로,
     * 마지막 세션이 종료되기 전까지 user_status:{userIdentifier}를 ONLINE으로 유지하기 위해 사용한다.
     *
     * @param userIdentifier 사용자 식별자
     * @return "user_status:{userIdentifier}:sessions"
     */
    public static String userStatusSessionsKey(String userIdentifier) {
        return USER_STATUS_PREFIX + userIdentifier + ":sessions";
    }

    /**
     * WebSocket 세션 매핑 Hash 키를 반환합니다.
     * 저장 구조: Hash { userId, lobbyCode }
     * DISCONNECT 이벤트에서 userIdentifier와 lobbyCode를 역추적하는 데 사용됩니다.
     *
     * @param wsSessionId WebSocket 세션 ID
     * @return "ws:connection:{wsSessionId}"
     */
    public static String wsConnectionKey(String wsSessionId) {
        return WS_CONNECTION_PREFIX + wsSessionId;
    }

    /**
     * 게스트 세션 정보를 저장하는 Redis Hash 키를 반환합니다.
     *
     * @param guestToken 게스트 UUID 토큰
     * @return "auth:guest:session:{guestToken}"
     */
    public static String guestSessionKey(String guestToken) {
        return GUEST_SESSION_PREFIX + guestToken;
    }

    /**
     * Refresh Token 저장 키를 반환합니다.
     *
     * @param sessionId 세션 식별자(UUID)
     * @return "auth:refresh:{sessionId}"
     */
    public static String refreshTokenKey(String sessionId) {
        return REFRESH_TOKEN_PREFIX + sessionId;
    }

    /**
     * 초대 코드 중복 방지 SETNX 락 키를 반환한다.
     *
     * [SETNX 전략]
     * Redis SET NX 명령으로 원자적으로 코드를 선점한다.
     * 선점 성공 시 해당 코드를 사용하고, 실패 시 재생성한다.
     * TTL은 LobbyDefaults.INVITE_CODE_LOCK_TTL을 따르며 생성 실패 시 자동 해제되어 코드 공간을 반환한다.
     *
     * @param inviteCode 로비 초대 코드
     * @return "lobby:code:lock:{inviteCode}"
     */
    public static String lobbyCodeLockKey(String inviteCode) {
        return LOBBY_CODE_LOCK_PREFIX + inviteCode;
    }

    /**
     * 로비 내 특정 사용자의 현재 유효 WebSocket 세션 ID를 저장하는 키를 반환합니다.
     *
     * [사용 목적]
     * 동일 userIdentifier가 같은 로비에 여러 번 연결될 수 있는 상황에서
     * 어떤 wsSessionId가 현재 유효한 세션인지 판별하기 위해 사용합니다.
     *
     * 저장 구조:
     * - Key   : lobby:{code}:user_session:{userIdentifier}
     * - Value : wsSessionId
     *
     * @param code 로비 초대 코드
     * @param userIdentifier 사용자 식별자
     * @return "lobby:{code}:user_session:{userIdentifier}"
     */
    public static String lobbyUserSessionKey(String code, String userIdentifier) {
        return LOBBY_PREFIX + code + USER_SESSION_SUFFIX + userIdentifier;
    }

    /**
     * 로비 내 특정 사용자의 현재 유효 WebSocket 세션 sequence를 저장하는 키를 반환합니다.
     *
     * [사용 목적]
     * 동일 userIdentifier가 같은 로비에 거의 동시에 재접속하는 경우,
     * 오래된 세션의 늦은 SUBSCRIBE가 최신 세션을 덮어쓰지 않도록 sequence 비교에 사용합니다.
     *
     * 저장 구조:
     * - Key   : lobby:{code}:user_session_seq:{userIdentifier}
     * - Value : sessionSequence
     *
     * @param code 로비 초대 코드
     * @param userIdentifier 사용자 식별자
     * @return "lobby:{code}:user_session_seq:{userIdentifier}"
     */
    public static String lobbyUserSessionSequenceKey(String code, String userIdentifier) {
        return LOBBY_PREFIX + code + USER_SESSION_SEQUENCE_SUFFIX + userIdentifier;
    }

    /**
     * 공개 맵 목록 캐시 버전 키를 반환합니다.
     *
     * @return "map:public:list:version"
     */
    public static String mapPublicListVersionKey() {
        return MAP_PUBLIC_LIST_VERSION;
    }

    /**
     * 공개 맵 목록 페이지 캐시 키를 반환합니다.
     *
     * @param version 캐시 버전
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return "map:public:list:v:{version}:p:{page}:s:{size}"
     */
    public static String mapPublicListKey(String version, int page, int size) {
        return MAP_PUBLIC_LIST_PREFIX + ":v:" + version + ":p:" + page + ":s:" + size;
    }

    /**
     * 공개 맵 단건 캐시 키를 반환합니다.
     *
     * @param mapId 맵 ID
     * @return "map:public:{mapId}"
     */
    public static String mapPublicDetailKey(Long mapId) {
        return MAP_PUBLIC_DETAIL_PREFIX + mapId;
    }

    /**
     * YouTube oEmbed 성공 캐시 키를 반환합니다.
     *
     * @param videoId YouTube videoId
     * @return "youtube:oembed:success:{videoId}"
     */
    public static String youtubeOembedSuccessKey(String videoId) {
        return YOUTUBE_OEMBED_SUCCESS_PREFIX + videoId;
    }

    /**
     * YouTube oEmbed 실패 캐시 키를 반환합니다.
     *
     * @param urlHash URL 해시
     * @return "youtube:oembed:failure:{urlHash}"
     */
    public static String youtubeOembedFailureKey(String urlHash) {
        return YOUTUBE_OEMBED_FAILURE_PREFIX + urlHash;
    }
}
