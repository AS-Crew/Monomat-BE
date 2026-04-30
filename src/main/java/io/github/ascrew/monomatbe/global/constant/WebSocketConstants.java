package io.github.ascrew.monomatbe.global.constant;

public class WebSocketConstants {
    private WebSocketConstants() {
        // 유틸리티 클래스의 인스턴스화 방지
        throw new UnsupportedOperationException("Utility class");
    }

    // WebSocket/STOMP endpoint and prefixes
    public static final String WS_ENDPOINT = "/ws";
    public static final String APP_DESTINATION_PREFIX = "/app";
    public static final String TOPIC_DESTINATION_PREFIX = "/topic";

    // Chat destinations and mappings
    public static final String CHAT_GLOBAL_MAPPING = "/chat/global";
    public static final String CHAT_LOBBY_MAPPING = "/chat/lobby/{code}";
    public static final String CHAT_GLOBAL_TOPIC = TOPIC_DESTINATION_PREFIX + "/chat/global";

    // Lobby topics
    public static final String LOBBY_TOPIC_PREFIX = TOPIC_DESTINATION_PREFIX + "/lobby/";
    public static final String LOBBY_TOPIC_PATTERN = LOBBY_TOPIC_PREFIX + "*";

    // STOMP 헤더 및 세션 어트리뷰트 키
    public static final String HEADER_UUID = "uuid";
    public static final String SESSION_ATTR_UUID = "uuid";
    public static final String SESSION_ATTR_ROOM_ID = "roomId";

    // 기본값 (Fallback)
    public static final String UNKNOWN_USER = "UNKNOWN";
}
