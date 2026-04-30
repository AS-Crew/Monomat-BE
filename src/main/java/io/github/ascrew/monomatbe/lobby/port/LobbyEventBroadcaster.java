package io.github.ascrew.monomatbe.lobby.port;

// 도메인 이벤트를 외부로 전파하는 인터페이스
public interface LobbyEventBroadcaster {
    void broadcastLobbyDestroyed(String lobbyCode);
    void broadcastHostDelegated(String lobbyCode, String newHostId);
    void broadcastUserLeft(String lobbyCode, String userId);
    void broadcastLobbyListRefresh();
    void broadcastLobbyInfoRefresh(String lobbyCode);

}

