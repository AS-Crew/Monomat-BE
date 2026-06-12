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
 *
 * isLobbyChatSubscription() 추가
 * - isLobbySubscription()  : /topic/lobby/** 전체 매칭 (리프레시 채널 포함)
 * - isLobbyChatSubscription(): /topic/lobby/{code} 정확히 매칭 (하위 경로 제외)
 * - 입장/퇴장 처리 트리거는 채팅 채널 구독 시점에만 발생해야 하므로 분리
 */
package io.github.ascrew.monomatbe.global.constant;

public final class StompDestinations {

    // 인스턴스화 방지
    private StompDestinations() {}

    // =========================================================
    // Prefix 상수 (WebSocketConfig에서 설정한 값과 일치해야 함)
    // =========================================================

    private static final String PUBLISH_PREFIX = "/app";
    private static final String SUBSCRIBE_PREFIX = "/topic";

    /** 로비 채널 공통 접두사 — 내부 판별 메서드에서만 사용 */
    private static final String LOBBY_CHANNEL_PREFIX = SUBSCRIBE_PREFIX + "/lobby/";

    // =========================================================
    // 고정 구독 경로
    // =========================================================

    /** 전체 채팅 구독 경로 */
    public static final String SUBSCRIBE_GLOBAL_CHAT = SUBSCRIBE_PREFIX + "/chat/global";

    /** 로비 리스트 새로고침 신호 구독 경로 */
    public static final String SUBSCRIBE_LOBBY_LIST_REFRESH = SUBSCRIBE_PREFIX + "/lobby/refresh";

    /** 로비 채팅 채널 패턴 구독 경로 (RedisMessageListenerContainer 패턴용) */
    public static final String SUBSCRIBE_LOBBY_PATTERN = SUBSCRIBE_PREFIX + "/lobby/*";

    // =========================================================
    // 고정 송신 경로
    // =========================================================

    /** 전체 채팅 송신 경로 */
    public static final String PUBLISH_GLOBAL_CHAT = PUBLISH_PREFIX + "/chat/global";

    /** 로비 생성 이벤트 송신 경로 */
    public static final String PUBLISH_LOBBY_CREATE = PUBLISH_PREFIX + "/lobby/create";

    // =========================================================
    // 브로드캐스트 메시지 상수
    // =========================================================

    public static final String MSG_REFRESH_LOBBY_LIST = "REFRESH_LOBBY_LIST";
    public static final String MSG_REFRESH_LOBBY_INFO = "REFRESH_LOBBY_INFO";
    public static final String MSG_GAME_STARTED = "GAME_STARTED";

    // =========================================================
    // 동적 경로 생성 팩토리 메서드
    // =========================================================

    /** @return "/topic/lobby/{code}" */
    public static String subscribeLobbyChat(String code) {
        return LOBBY_CHANNEL_PREFIX + code;
    }

    /** @return "/topic/lobby/{code}/refresh" */
    public static String subscribeLobbyRefresh(String code) {
        return LOBBY_CHANNEL_PREFIX + code + "/refresh";
    }

    /** @return "/topic/lobby/{code}/game" */
    public static String subscribeLobbyGame(String code) {
        return LOBBY_CHANNEL_PREFIX + code + "/game";
    }

    /** @return "/topic/game/{code}/round" */
    public static String subscribeGameRound(String code) {
        return SUBSCRIBE_PREFIX + "/game/" + code + "/round";
    }

    /** @return "/topic/game/{code}/round-end" */
    public static String subscribeGameRoundEnd(String code) {
        return SUBSCRIBE_PREFIX + "/game/" + code + "/round-end";
    }

    /** @return "/topic/game/{code}/chat" */
    public static String subscribeGameChat(String code) {
        return SUBSCRIBE_PREFIX + "/game/" + code + "/chat";
    }

    /** @return "/app/game/{code}/chat" */
    public static String publishGameChat(String code) {
        return PUBLISH_PREFIX + "/game/" + code + "/chat";
    }

    /** @return "/app/game/{code}/ready-to-play" */
    public static String publishGameReadyToPlay(String code) {
        return PUBLISH_PREFIX + "/game/" + code + "/ready-to-play";
    }

    /** @return "/app/game/{code}/playback-error" */
    public static String publishGamePlaybackError(String code) {
        return PUBLISH_PREFIX + "/game/" + code + "/playback-error";
    }

    /** 인게임 정답 개별 통지 서버 전송용 User Queue 경로 (convertAndSendToUser 인자용) */
    public static final String SERVER_USER_GAME_ANSWERS = "/queue/game/answers";

    /** 인게임 정답 개별 통지 클라이언트 구독 경로 */
    public static final String SUBSCRIBE_USER_GAME_ANSWERS = "/user/queue/game/answers";

    /** @return "/app/chat/lobby/{code}" */
    public static String publishLobbyChat(String code) {
        return PUBLISH_PREFIX + "/chat/lobby/" + code;
    }

    /** @return "/app/lobby/{code}/update" */
    public static String publishLobbyUpdate(String code) {
        return PUBLISH_PREFIX + "/lobby/" + code + "/update";
    }

    // =========================================================
    // 채널 판별 메서드
    // =========================================================

    /**
     * 로비 관련 채널 전체를 판별합니다 (/topic/lobby/** 포함).
     * StompChannelInterceptor에서 로비 코드를 세션에 저장할 때 사용합니다.
     */
    public static boolean isLobbySubscription(String destination) {
        return destination != null && destination.startsWith(LOBBY_CHANNEL_PREFIX);
    }

    /**
     * 로비 채팅 채널만 정확히 판별합니다.
     *
     * [판별 기준]
     * LOBBY_CHANNEL_PREFIX 이후에 슬래시(/)가 없는 경우만 채팅 채널로 판단합니다.
     * - /topic/lobby/ABC123         → true  (채팅 채널)
     * - /topic/lobby/ABC123/refresh → false (리프레시 채널)
     * - /topic/lobby/ABC123/game    → false (향후 추가 채널도 자동 차단)
     *
     * [용도]
     * 입장 처리(참여자 추가, 세션 매핑, ENTER 브로드캐스트) 트리거에만 사용합니다.
     */
    public static boolean isLobbyChatSubscription(String destination) {
        if (destination == null) return false;
        if (!destination.startsWith(LOBBY_CHANNEL_PREFIX)) return false;

        String afterPrefix = destination.substring(LOBBY_CHANNEL_PREFIX.length());
        // 코드가 비어있지 않고 슬래시가 없어야 /topic/lobby/{code} 형태
        return !afterPrefix.isEmpty() && !afterPrefix.contains("/");
    }

    /**
     * 로비 구독 경로에서 로비 코드를 추출합니다.
     * "/topic/lobby/{code}" 또는 "/topic/lobby/{code}/..." 형태에서 코드를 파싱합니다.
     */
    public static String extractLobbyCode(String destination) {
        String afterPrefix = destination.substring(LOBBY_CHANNEL_PREFIX.length());
        int slashIndex = afterPrefix.indexOf('/');
        return slashIndex == -1 ? afterPrefix : afterPrefix.substring(0, slashIndex);
    }
}
