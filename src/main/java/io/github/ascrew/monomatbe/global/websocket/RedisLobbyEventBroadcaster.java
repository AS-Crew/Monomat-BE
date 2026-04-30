package io.github.ascrew.monomatbe.global.websocket;


import io.github.ascrew.monomatbe.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.service.RedisPublisher;
import io.github.ascrew.monomatbe.service.port.LobbyEventBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class RedisLobbyEventBroadcaster implements LobbyEventBroadcaster {

    private final RedisPublisher redisPublisher;
    private static final String LOBBY_TOPIC_PREFIX = "/topic/lobby/";

    @Override
    public void broadcastLobbyDestroyed(String lobbyCode) {
        // 방이 폭파되었음을 알리는 메시지 (또는 로비 리스트 갱신 신호)
        publishLeaveMessage(lobbyCode, "방장이 퇴장하여 로비가 해제되었습니다.");
    }

    @Override
    public void broadcastHostDelegated(String lobbyCode, String newHostId) {
        // 방장 위임 메시지
        publishLeaveMessage(lobbyCode, newHostId + "님이 새로운 방장이 되었습니다.");
    }

    @Override
    public void broadcastUserLeft(String lobbyCode, String userId) {
        // (선택) WebSocketEventListener에서 이미 퇴장 메시지를 쏘고 있다면,
        // 여기서는 데이터 갱신(Refresh) 이벤트만 쏠 수도 있습니다.
    }

    private void publishLeaveMessage(String lobbyCode, String content) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.LEAVE)
                .roomId(lobbyCode)
                .sender("SYSTEM")
                .content(content)
                .build();
        redisPublisher.publish(LOBBY_TOPIC_PREFIX + lobbyCode, message);
    }
}