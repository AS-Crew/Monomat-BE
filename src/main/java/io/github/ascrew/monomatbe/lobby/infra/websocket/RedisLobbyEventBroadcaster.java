package io.github.ascrew.monomatbe.lobby.infra.websocket;


import io.github.ascrew.monomatbe.messaging.dto.ChatMessageDto;
import io.github.ascrew.monomatbe.messaging.redis.RedisPublisher;
import io.github.ascrew.monomatbe.lobby.port.LobbyEventBroadcaster;
import io.github.ascrew.monomatbe.common.constant.WebSocketConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class RedisLobbyEventBroadcaster implements LobbyEventBroadcaster {

    private final RedisPublisher redisPublisher;

    @Override
    public void broadcastLobbyDestroyed(String lobbyCode) {
        // 방이 폭파되었음을 알리는 메시지 (또는 로비 리스트 갱신 신호)
        publishLeaveMessage(lobbyCode, "SYSTEM", "방장이 퇴장하여 로비가 해제되었습니다.");
    }

    @Override
    public void broadcastHostDelegated(String lobbyCode, String newHostId) {
        // 방장 위임 메시지
        publishLeaveMessage(lobbyCode, "SYSTEM", newHostId + "님이 새로운 방장이 되었습니다.");
    }

    @Override
    public void broadcastUserLeft(String lobbyCode, String userId) {
        // (선택) WebSocketEventListener에서 이미 퇴장 메시지를 쏘고 있다면,
        // 여기서는 데이터 갱신(Refresh) 이벤트만 쏠 수도 있습니다.
    }

    private void publishLeaveMessage(String lobbyCode, String sender, String content) {
        ChatMessageDto message = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.LEAVE)
                .roomId(lobbyCode)
                .sender(sender)
                .content(content)
                .build();
        redisPublisher.publish(WebSocketConstants.LOBBY_TOPIC_PREFIX + lobbyCode, message);
    }

    @Override
    public void broadcastLobbyListRefresh() {
        // 'public' 또는 특정 글로벌 채널을 통해 로비 리스트 갱신 신호를 보냅니다.
        // 프론트엔드와 약속한 목적지로 메시지를 전송하도록 수정하세요.
        publishLeaveMessage("public", "SYSTEM", "REFRESH_LIST");
    }

    @Override
    public void broadcastLobbyInfoRefresh(String lobbyCode) {
        publishLeaveMessage(lobbyCode, "SYSTEM", "REFRESH_INFO");
    }
}
