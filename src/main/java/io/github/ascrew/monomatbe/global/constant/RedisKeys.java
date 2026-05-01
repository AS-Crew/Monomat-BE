/*
 * Redis에서 사용하는 키 패턴을 중앙에서 관리하는 상수 클래스.
 *
 * [네이밍 규칙]
 * - PREFIX : 키의 접두사 (단독으로 사용하지 않음)
 * - 정적 메서드 : 동적 파라미터가 필요한 키를 생성하는 팩토리 메서드
 *
 * [사용 예시]
 * RedisKeys.lobbyKey("ABC123")          → "lobby:ABC123"
 * RedisKeys.lobbyParticipantsKey("ABC") → "lobby:ABC:participants"
 * RedisKeys.userStatusKey("uuid-1234")  → "user_status:uuid-1234"
 */
package io.github.ascrew.monomatbe.global.constant;

public final class RedisKeys {

    // 인스턴스화 방지
    private RedisKeys() {}

    // =========================================================
    // Prefix 상수
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

    /** 로비별 참여자 Set 키 접두사 (WebSocketEventListener용) */
    private static final String USER_ROOM_PREFIX = "user_room:";

    // =========================================================
    // 전역 단일 키 상수
    // =========================================================

    /** 공개 로비 코드 목록을 담는 전역 Set 키 */
    public static final String LOBBY_PUBLIC = "lobby:public";

    // =========================================================
    // 동적 키 생성 팩토리 메서드
    // =========================================================

    /**
     * 로비 메타 정보 Hash 키를 반환합니다.
     * 저장 구조: Hash { host_user_id, title, status, ... }
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
     *
     * @param userIdentifier 사용자 식별자
     * @return "user_status:{userIdentifier}"
     */
    public static String userStatusKey(String userIdentifier) {
        return USER_STATUS_PREFIX + userIdentifier;
    }

    /**
     * WebSocket 세션 매핑 Hash 키를 반환합니다.
     * 저장 구조: Hash { userId, lobbyCode }
     *
     * @param wsSessionId WebSocket 세션 ID
     * @return "ws:connection:{wsSessionId}"
     */
    public static String wsConnectionKey(String wsSessionId) {
        return WS_CONNECTION_PREFIX + wsSessionId;
    }

    /**
     * 로비별 참여자 Set 키를 반환합니다. (WebSocketEventListener 전용)
     * WebSocket 구독 기반으로 참여자를 추적하는 데 사용됩니다.
     *
     * @param roomId 로비 코드
     * @return "user_room:{roomId}"
     */
    public static String userRoomKey(String roomId) {
        return USER_ROOM_PREFIX + roomId;
    }
}