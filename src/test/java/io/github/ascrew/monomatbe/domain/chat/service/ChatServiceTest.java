package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.constant.WebSocketHeaders;
import io.github.ascrew.monomatbe.global.redis.RedisPublisher;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String GLOBAL_ROOM_ID = "global";
    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final Long SENDER_ID = 1L;
    private static final String SENDER_NICKNAME = "테스터";
    private static final String MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
    private static final String SENT_AT = "2026-05-30T12:00:00.123Z";

    @Mock
    private RedisPublisher redisPublisher;

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private LobbyChatRateLimitService lobbyChatRateLimitService;

    @Mock
    private LobbyRecentChatStoreService lobbyRecentChatStoreService;

    @Mock
    private TrustedChatMessageFactory trustedChatMessageFactory;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                redisPublisher,
                lobbyRepository,
                lobbyChatRateLimitService,
                lobbyRecentChatStoreService,
                trustedChatMessageFactory
        );
    }

    @Test
    @DisplayName("로비 참여자는 정상 채팅을 전송할 수 있고 서버 신뢰 메시지를 저장/발행한다")
    void publishLobbyMessage_success() {
        // given
        ChatMessageDto request = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId("FAKE_ROOM")
                .sender("spoofed-user")
                .content("  안녕하세요  ")
                .timestamp("2000-01-01T00:00:00.000Z")
                .build();

        ChatMessageDto secureMessage = secureLobbyMessage("안녕하세요");

        givenValidLobbyPermission();

        when(trustedChatMessageFactory.createUserChatMessage(
                request,
                LOBBY_CODE,
                USER_IDENTIFIER
        )).thenReturn(secureMessage);

        SimpMessageHeaderAccessor accessor = accessorWithUserIdentifier();

        // when
        chatService.publishLobbyMessage(LOBBY_CODE, request, accessor);

        // then
        verify(trustedChatMessageFactory).createUserChatMessage(
                request,
                LOBBY_CODE,
                USER_IDENTIFIER
        );

        verify(lobbyChatRateLimitService).validateAndRecord(
                LOBBY_CODE,
                USER_IDENTIFIER,
                "안녕하세요"
        );

        verify(lobbyRecentChatStoreService).append(
                LOBBY_CODE,
                secureMessage
        );

        ArgumentCaptor<ChatMessageDto> publishCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);

        verify(redisPublisher).publish(
                eq(StompDestinations.subscribeLobbyChat(LOBBY_CODE)),
                publishCaptor.capture()
        );

        ChatMessageDto published = publishCaptor.getValue();

        assertThat(published.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(published.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(published.getRoomId()).isEqualTo(LOBBY_CODE);
        assertThat(published.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(published.getSenderId()).isEqualTo(SENDER_ID);
        assertThat(published.getSenderNickname()).isEqualTo(SENDER_NICKNAME);
        assertThat(published.getContent()).isEqualTo("안녕하세요");
        assertThat(published.getTimestamp()).isEqualTo(SENT_AT);
        assertThat(published.getSentAt()).isEqualTo(SENT_AT);
    }

    @Test
    @DisplayName("존재하지 않는 로비에는 채팅을 보낼 수 없다")
    void publishLobbyMessage_failsWhenLobbyNotFound() {
        // given
        ChatMessageDto request = chatMessage("안녕하세요");

        when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
                chatService.publishLobbyMessage(LOBBY_CODE, request, accessorWithUserIdentifier())
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
                );

        verify(trustedChatMessageFactory, never()).createUserChatMessage(any(), any(), any());
        verify(lobbyChatRateLimitService, never()).validateAndRecord(any(), any(), any());
        verify(lobbyRecentChatStoreService, never()).append(any(), any());
        verify(redisPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("강퇴된 유저는 로비 채팅을 보낼 수 없다")
    void publishLobbyMessage_failsWhenUserKicked() {
        // given
        ChatMessageDto request = chatMessage("안녕하세요");

        when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(true);
        when(lobbyRepository.isKicked(LOBBY_CODE, USER_IDENTIFIER)).thenReturn(true);

        // when & then
        assertThatThrownBy(() ->
                chatService.publishLobbyMessage(LOBBY_CODE, request, accessorWithUserIdentifier())
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                );

        verify(lobbyRepository, never()).isParticipant(LOBBY_CODE, USER_IDENTIFIER);
        verify(trustedChatMessageFactory, never()).createUserChatMessage(any(), any(), any());
        verify(lobbyChatRateLimitService, never()).validateAndRecord(any(), any(), any());
        verify(lobbyRecentChatStoreService, never()).append(any(), any());
        verify(redisPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("로비 참여자가 아니면 로비 채팅을 보낼 수 없다")
    void publishLobbyMessage_failsWhenNotParticipant() {
        // given
        ChatMessageDto request = chatMessage("안녕하세요");

        when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(true);
        when(lobbyRepository.isKicked(LOBBY_CODE, USER_IDENTIFIER)).thenReturn(false);
        when(lobbyRepository.isParticipant(LOBBY_CODE, USER_IDENTIFIER)).thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
                chatService.publishLobbyMessage(LOBBY_CODE, request, accessorWithUserIdentifier())
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                );

        verify(trustedChatMessageFactory, never()).createUserChatMessage(any(), any(), any());
        verify(lobbyChatRateLimitService, never()).validateAndRecord(any(), any(), any());
        verify(lobbyRecentChatStoreService, never()).append(any(), any());
        verify(redisPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("서버 신뢰 메시지 생성 실패 시 채팅 저장과 발행을 수행하지 않는다")
    void publishLobbyMessage_failsWhenTrustedMessageCreationFails() {
        // given
        ChatMessageDto request = chatMessage("     ");

        givenValidLobbyPermission();

        when(trustedChatMessageFactory.createUserChatMessage(
                request,
                LOBBY_CODE,
                USER_IDENTIFIER
        )).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "채팅 메시지를 입력해주세요."));

        // when & then
        assertThatThrownBy(() ->
                chatService.publishLobbyMessage(LOBBY_CODE, request, accessorWithUserIdentifier())
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );

        verify(lobbyChatRateLimitService, never()).validateAndRecord(any(), any(), any());
        verify(lobbyRecentChatStoreService, never()).append(any(), any());
        verify(redisPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("전체 채팅은 로비 참여자 검증과 최근 채팅 저장 없이 CHAT 메시지를 발행한다")
    void publishGlobalMessage_success() {
        // given
        ChatMessageDto request = chatMessage("  전체 채팅  ");
        ChatMessageDto secureMessage = secureGlobalMessage("전체 채팅");

        when(trustedChatMessageFactory.createUserChatMessage(
                request,
                GLOBAL_ROOM_ID,
                USER_IDENTIFIER
        )).thenReturn(secureMessage);

        SimpMessageHeaderAccessor accessor = accessorWithUserIdentifier();

        // when
        chatService.publishGlobalMessage(request, accessor);

        // then
        verify(lobbyRepository, never()).existsByCode(any());
        verify(lobbyChatRateLimitService, never()).validateAndRecord(any(), any(), any());
        verify(lobbyRecentChatStoreService, never()).append(any(), any());

        ArgumentCaptor<ChatMessageDto> publishCaptor = ArgumentCaptor.forClass(ChatMessageDto.class);

        verify(redisPublisher).publish(
                eq(StompDestinations.SUBSCRIBE_GLOBAL_CHAT),
                publishCaptor.capture()
        );

        ChatMessageDto published = publishCaptor.getValue();

        assertThat(published.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(published.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(published.getRoomId()).isEqualTo(GLOBAL_ROOM_ID);
        assertThat(published.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(published.getSenderId()).isEqualTo(SENDER_ID);
        assertThat(published.getSenderNickname()).isEqualTo(SENDER_NICKNAME);
        assertThat(published.getContent()).isEqualTo("전체 채팅");
        assertThat(published.getTimestamp()).isEqualTo(SENT_AT);
        assertThat(published.getSentAt()).isEqualTo(SENT_AT);
    }

    private void givenValidLobbyPermission() {
        when(lobbyRepository.existsByCode(LOBBY_CODE)).thenReturn(true);
        when(lobbyRepository.isKicked(LOBBY_CODE, USER_IDENTIFIER)).thenReturn(false);
        when(lobbyRepository.isParticipant(LOBBY_CODE, USER_IDENTIFIER)).thenReturn(true);
    }

    private ChatMessageDto chatMessage(String content) {
        return ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .content(content)
                .build();
    }

    private ChatMessageDto secureLobbyMessage(String content) {
        return secureMessage(LOBBY_CODE, content);
    }

    private ChatMessageDto secureGlobalMessage(String content) {
        return secureMessage(GLOBAL_ROOM_ID, content);
    }

    private ChatMessageDto secureMessage(String roomId, String content) {
        return ChatMessageDto.builder()
                .messageId(MESSAGE_ID)
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(roomId)
                .sender(USER_IDENTIFIER)
                .senderId(SENDER_ID)
                .senderNickname(SENDER_NICKNAME)
                .content(content)
                .timestamp(SENT_AT)
                .sentAt(SENT_AT)
                .build();
    }

    private SimpMessageHeaderAccessor accessorWithUserIdentifier() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();

        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(WebSocketHeaders.USER_IDENTIFIER, USER_IDENTIFIER);

        accessor.setSessionAttributes(sessionAttributes);

        return accessor;
    }
}