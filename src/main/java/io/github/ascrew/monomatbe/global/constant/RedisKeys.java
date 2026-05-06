/*
 * Redis에서 사용하는 키 패턴을 중앙에서 관리하는 상수 클래스.
 *
 * [네이밍 규칙]
 * - PREFIX  : 키의 접두사 (단독으로 사용하지 않음, private)
 * - FIELD_* : Redis Hash 내부 필드명 상수 (public)
 * - 정적 메서드 : 동적 파라미터가 필요한 키를 생성하는 팩토리 메서드
 *
 * [리팩토링 변경 사항]
 * 1. FIELD_* 상수 추가
 *    LobbyRepositoryImpl에서 Redis Hash 필드 키를 문자열 리터럴로 직접 사용하여
 *    오타 위험과 저장/조회 불일치 문제가 있었습니다.
 *    FIELD_* 상수로 통일하여 컴파일 타임에 오타를 방지합니다.
 *
 * 2. USER_ROOM_PREFIX 및 userRoomKey() 제거
 *    user_room:{lobbyCode} Set과 lobby:{code}:participants Set이 동일한 데이터를
 *    이중으로 관리하는 문제가 있었습니다.
 *    lobby:{code}:participants를 단일 진실의 원천(Source of Truth)으로 통일하고
 *    user_room 관련 상수와 메서드를 제거합니다.
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

    /** WebSocket 세션 매핑 Hash 키 접두사 */
    private static final String WS_CONNECTION_PREFIX = "ws:connection:";

    /** 게스트 세션 저장 키 접두사 */
    private static final String GUEST_SESSION_PREFIX = "auth:guest:session:";

    /** Refresh Token 저장 키 접두사 */
    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";

    /** 초대 코드 중복 방지 SETNX 락 키 접두사 */
    private static final String LOBBY_CODE_LOCK_PREFIX = "lobby:code:lock:";

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

    /**
     * 사용자 온라인 상태 키를 반환합니다.
     * 저장 구조: String "ONLINE"
     * TTL: WebSocketEventListener에서 2시간으로 설정
     *
     * @param userIdentifier 사용자 식별자 (게스트 UUID 또는 회원 ID)
     * @return "user_status:{userIdentifier}"
     */
    public static String userStatusKey(String userIdentifier) {
        return USER_STATUS_PREFIX + userIdentifier;
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

    // 초대 코드 SETNX 락 키 팩토리 메서드
    /**
     * 초대 코드 중복 방지 SETNX 락 키를 반환한다.
     *
     * [SETNX 전략]
     * Redis SET NX 명령으로 원자적으로 코드를 선점한다.
     * 선점 성공 시 해당 코드를 사용하고, 실패 시 재생성한다.
     * TTL은 LobbyDefaults.INVITE_CODE_LOCK_TTL을 따르며 생성 실패 시 자동 해제되어 코드 공간을 반환한다.
     */
    public static String lobbyCodeLockKey(String inviteCode) {
        return LOBBY_CODE_LOCK_PREFIX + inviteCode;
    }
}
