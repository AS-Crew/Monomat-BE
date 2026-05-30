package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LobbyRealtimeNotifierTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_HOST_IDENTIFIER = "22222222-2222-2222-2222-222222222222";

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private JsonMapper pubSubJsonMapper;

    private LobbyRealtimeNotifier lobbyRealtimeNotifier;

    @BeforeEach
    void setUp() {
        lobbyRealtimeNotifier = new LobbyRealtimeNotifier(
                messagingTemplate,
                lobbyRepository,
                pubSubJsonMapper
        );
    }

    @Test
    @DisplayName("READY_CHANGED 시스템 메시지를 로비 채팅 채널로 발행한다")
    void notifyReadyChangedMessage_success() {
        // given
        when(pubSubJsonMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(ChatMessageDto.class)))
                .thenReturn("{\"type\":\"READY_CHANGED\"}");

        // when
        boolean result = lobbyRealtimeNotifier.notifyReadyChangedMessage(
                LOBBY_CODE,
                USER_IDENTIFIER,
                true
        );

        // then
        assertThat(result).isTrue();

        ArgumentCaptor<ChatMessageDto> messageCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);

        verify(pubSubJsonMapper).writeValueAsString(messageCaptor.capture());
        verify(messagingTemplate).convertAndSend(
                eq(StompDestinations.subscribeLobbyChat(LOBBY_CODE)),
                eq("{\"type\":\"READY_CHANGED\"}")
        );

        ChatMessageDto message = messageCaptor.getValue();

        assertThat(message.getType()).isEqualTo(ChatMessageDto.MessageType.READY_CHANGED);
        assertThat(message.getRoomId()).isEqualTo(LOBBY_CODE);
        assertThat(message.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(message.getContent()).contains("준비 완료");
        assertThat(message.getTimestamp()).isNotBlank();
    }

    @Test
    @DisplayName("HOST_CHANGED 시스템 메시지를 로비 채팅 채널로 발행한다")
    void notifyHostChangedMessage_success() {
        // given
        when(pubSubJsonMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(ChatMessageDto.class)))
                .thenReturn("{\"type\":\"HOST_CHANGED\"}");

        // when
        boolean result = lobbyRealtimeNotifier.notifyHostChangedMessage(
                LOBBY_CODE,
                NEW_HOST_IDENTIFIER
        );

        // then
        assertThat(result).isTrue();

        ArgumentCaptor<ChatMessageDto> messageCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);

        verify(pubSubJsonMapper).writeValueAsString(messageCaptor.capture());
        verify(messagingTemplate).convertAndSend(
                eq(StompDestinations.subscribeLobbyChat(LOBBY_CODE)),
                eq("{\"type\":\"HOST_CHANGED\"}")
        );

        ChatMessageDto message = messageCaptor.getValue();

        assertThat(message.getType()).isEqualTo(ChatMessageDto.MessageType.HOST_CHANGED);
        assertThat(message.getRoomId()).isEqualTo(LOBBY_CODE);
        assertThat(message.getSender()).isEqualTo(NEW_HOST_IDENTIFIER);
        assertThat(message.getContent()).contains("새로운 방장");
        assertThat(message.getTimestamp()).isNotBlank();
    }

    @Test
    @DisplayName("KICK 시스템 메시지를 로비 채팅 채널로 발행한다")
    void notifyKickMessage_success() {
        // given
        when(pubSubJsonMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(ChatMessageDto.class)))
                .thenReturn("{\"type\":\"KICK\"}");

        // when
        boolean result = lobbyRealtimeNotifier.notifyKickMessage(
                LOBBY_CODE,
                USER_IDENTIFIER
        );

        // then
        assertThat(result).isTrue();

        ArgumentCaptor<ChatMessageDto> messageCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);

        verify(pubSubJsonMapper).writeValueAsString(messageCaptor.capture());
        verify(messagingTemplate).convertAndSend(
                eq(StompDestinations.subscribeLobbyChat(LOBBY_CODE)),
                eq("{\"type\":\"KICK\"}")
        );

        ChatMessageDto message = messageCaptor.getValue();

        assertThat(message.getType()).isEqualTo(ChatMessageDto.MessageType.KICK);
        assertThat(message.getRoomId()).isEqualTo(LOBBY_CODE);
        assertThat(message.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(message.getContent()).contains("강퇴");
        assertThat(message.getTimestamp()).isNotBlank();
    }

    @Test
    @DisplayName("시스템 메시지 직렬화 실패 시 false를 반환한다")
    void notifySystemMessage_returnsFalseWhenSerializeFails() {
        // given
        when(pubSubJsonMapper.writeValueAsString(org.mockito.ArgumentMatchers.any(ChatMessageDto.class)))
                .thenThrow(new RuntimeException("serialize failed"));

        // when
        boolean result = lobbyRealtimeNotifier.notifyReadyChangedMessage(
                LOBBY_CODE,
                USER_IDENTIFIER,
                true
        );

        // then
        assertThat(result).isFalse();
    }
}