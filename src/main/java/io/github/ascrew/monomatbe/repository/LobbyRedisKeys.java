package io.github.ascrew.monomatbe.repository;

public class LobbyRedisKeys {
    private static final String PREFIX = "lobby:";

    public static String getLobbyMetaKey(String code) {
        return PREFIX + code; // lobby:ABCDEF (Hash: 방 정보)
    }

    public static String getParticipantsKey(String code) {
        return PREFIX + code + ":participants"; // lobby:ABCDEF:participants (Set: 유저 목록)
    }

    public static String getOrderKey(String code) {
        return PREFIX + code + ":order"; // lobby:ABCDEF:order (List: 입장 순서)
    }

    public static String getPublicLobbiesKey() {
        return "lobby:public"; // lobby:public (Set/ZSet: 공개 방 목록 인덱스)
    }

    public static String getConnectionKey(String sessionId) {
        return "ws:connection:" + sessionId; // ws:connection:{sessionId} (Hash: 세션 매핑 정보)
    }
}
