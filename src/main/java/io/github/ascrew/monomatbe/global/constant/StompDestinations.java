/*
 * STOMP WebSocket의 송신/수신 경로를 중앙에서 관리하는 상수 클래스.
 *
 * [경로 규칙]
 * - PUBLISH_*  : 클라이언트가 서버로 메시지를 보내는 경로 (/app 접두사 포함)
 * - SUBSCRIBE_*: 클라이언트가 구독하는 경로 (/topic 접두사 포함)
 *
 * [사용 예시]
 * StompDestinations.subscribeLobbyChat("ABC123")
 *     → "/topic/lobby/ABC123"
 * StompDestinations.subscribeLobbyRefresh("ABC123")
 *     → "/topic/lobby/ABC123/refresh"
 */
package io.github.ascrew.monomatbe.global.constant;

public final class StompDestinations {

    // 인스턴스화 방지
    private StompDestinations() {}

    // =========================================================
    // Prefix 상수 (WebSocketConfig에서 설정한 값과 일치해야 함)
    // =========================================================

    /** 클라이언트 → 서버 송신 경로 접두사 (WebSocketConfig applicationDestinationPrefix) */
    private static final String PUBLISH_PREFIX = "/app";

    /** 서버 → 클라이언트 수신 경로 접두사 (WebSocketConfig simpleBroker) */
    private static final String SUBSCRIBE_PREFIX = "/topic";

    // =========================================================
    // 고정 구독 경로 (단순 상수)
    // =========================================================

    /** 전체 채팅 구독 경로 */
    public static final String SUBSCRIBE_GLOBAL_CHAT = SUBSCRIBE_PREFIX + "/chat/global";

    /** 로비 리스트 새로고침 신호 구독 경로 */
    public static final String SUBSCRIBE_LOBBY_LIST_REFRESH = SUBSCRIBE_PREFIX + "/lobby/refresh";

    /** 로비 채팅 채널 패턴 구독 경로 (RedisMessageListenerContainer 패턴용) */
    public static final String SUBSCRIBE_LOBBY_PATTERN = SUBSCRIBE_PREFIX + "/lobby/*";

    // =========================================================
    // 고정 송신 경로 (단순 상수)
    // =========================================================

    /** 전체 채팅 송신 경로 */
    public static final String PUBLISH_GLOBAL_CHAT = PUBLISH_PREFIX + "/chat/global";

    /** 로비 생성 이벤트 송신 경로 */
    public static final String PUBLISH_LOBBY_CREATE = PUBLISH_PREFIX + "/lobby/create";

    // =========================================================
    // 동적 경로 생성 팩토리 메서드
    // =========================================================

    /**
     * 로비 채팅 구독 경로를 반환합니다.
     *
     * @param code 로비 초대 코드
     * @return "/topic/lobby/{code}"
     */
    public static String subscribeLobbyChat(String code) {
        return SUBSCRIBE_PREFIX + "/lobby/" + code;
    }

    /**
     * 로비 내부 정보 새로고침 신호 구독 경로를 반환합니다.
     *
     * @param code 로비 초대 코드
     * @return "/topic/lobby/{code}/refresh"
     */
    public static String subscribeLobbyRefresh(String code) {
        return SUBSCRIBE_PREFIX + "/lobby/" + code + "/refresh";
    }

    /**
     * 로비 채팅 송신 경로를 반환합니다.
     *
     * @param code 로비 초대 코드
     * @return "/app/chat/lobby/{code}"
     */
    public static String publishLobbyChat(String code) {
        return PUBLISH_PREFIX + "/chat/lobby/" + code;
    }

    /**
     * 로비 내부 정보 변경 이벤트 송신 경로를 반환합니다.
     *
     * @param code 로비 초대 코드
     * @return "/app/lobby/{code}/update"
     */
    public static String publishLobbyUpdate(String code) {
        return PUBLISH_PREFIX + "/lobby/" + code + "/update";
    }

    /**
     * 주어진 경로가 로비 구독 채널인지 확인합니다.
     * 외부에서 SUBSCRIBE_PREFIX를 직접 참조하지 않도록 캡슐화합니다.
     *
     * @param destination 확인할 STOMP 경로
     * @return 로비 구독 채널 여부
     */
    public static boolean isLobbySubscription(String destination) {
        return destination != null
                && destination.startsWith(SUBSCRIBE_PREFIX + "/lobby/");
    }

    /**
     * 로비 구독 경로에서 로비 코드를 추출합니다.
     * "/topic/lobby/{code}" 또는 "/topic/lobby/{code}/..." 형태에서 코드를 파싱합니다.
     *
     * @param destination STOMP 구독 경로
     * @return 로비 코드
     */
    public static String extractLobbyCode(String destination) {
        // "/topic/lobby/" 이후의 문자열에서 "/" 이전까지가 로비 코드
        String afterPrefix = destination.substring((SUBSCRIBE_PREFIX + "/lobby/").length());
        int slashIndex = afterPrefix.indexOf('/');
        return slashIndex == -1 ? afterPrefix : afterPrefix.substring(0, slashIndex);
    }
}