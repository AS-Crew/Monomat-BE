package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserIdentifierProfile;
import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustedChatMessageFactoryTest {

    private static final String ROOM_ID = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
    private static final Long USER_ID = 1L;
    private static final String NICKNAME = "테스터";

    @Mock
    private ChatMessageIdGenerator chatMessageIdGenerator;

    @Mock
    private UserNicknameLookupService userNicknameLookupService;

    private TrustedChatMessageFactory trustedChatMessageFactory;

    @BeforeEach
    void setUp() {
        trustedChatMessageFactory = new TrustedChatMessageFactory(
                chatMessageIdGenerator,
                userNicknameLookupService
        );
    }

    @Test
    @DisplayName("사용자 채팅 메시지를 서버 신뢰 값으로 재구성한다")
    void createUserChatMessage_success() {
        // given
        ChatMessageDto request = ChatMessageDto.builder()
                .messageId("spoofed-message-id")
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId("FAKE_ROOM")
                .sender("spoofed-user")
                .senderId(999L)
                .senderNickname("위조닉네임")
                .content("  안녕하세요  ")
                .timestamp("2000-01-01T00:00:00.000Z")
                .sentAt("2000-01-01T00:00:00.000Z")
                .build();

        when(chatMessageIdGenerator.generate()).thenReturn(MESSAGE_ID);
        when(userNicknameLookupService.findProfileMapByUserIdentifiers(List.of(USER_IDENTIFIER)))
                .thenReturn(Map.of(
                        USER_IDENTIFIER,
                        new UserIdentifierProfile(USER_ID, NICKNAME)
                ));

        // when
        ChatMessageDto result = trustedChatMessageFactory.createUserChatMessage(
                request,
                ROOM_ID,
                USER_IDENTIFIER
        );

        // then
        assertThat(result.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(result.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(result.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(result.getSenderId()).isEqualTo(USER_ID);
        assertThat(result.getSenderNickname()).isEqualTo(NICKNAME);
        assertThat(result.getContent()).isEqualTo("안녕하세요");
        assertUtcTimestamp(result.getTimestamp());
        assertUtcTimestamp(result.getSentAt());
        assertThat(result.getTimestamp()).isEqualTo(result.getSentAt());
    }

    @Test
    @DisplayName("닉네임 프로필을 찾지 못해도 메시지 생성은 실패시키지 않는다")
    void createUserChatMessage_successWithoutProfile() {
        // given
        ChatMessageDto request = chatMessage("안녕하세요");

        when(chatMessageIdGenerator.generate()).thenReturn(MESSAGE_ID);
        when(userNicknameLookupService.findProfileMapByUserIdentifiers(List.of(USER_IDENTIFIER)))
                .thenReturn(Map.of());

        // when
        ChatMessageDto result = trustedChatMessageFactory.createUserChatMessage(
                request,
                ROOM_ID,
                USER_IDENTIFIER
        );

        // then
        assertThat(result.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(result.getSenderId()).isNull();
        assertThat(result.getSenderNickname()).isNull();
        assertThat(result.getContent()).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("공백 메시지는 전송할 수 없다")
    void createUserChatMessage_failsWhenBlankContent() {
        // given
        ChatMessageDto request = chatMessage("     ");

        // when & then
        assertThatThrownBy(() ->
                trustedChatMessageFactory.createUserChatMessage(request, ROOM_ID, USER_IDENTIFIER)
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("500자를 초과한 메시지는 전송할 수 없다")
    void createUserChatMessage_failsWhenContentTooLong() {
        // given
        ChatMessageDto request = chatMessage("가".repeat(501));

        // when & then
        assertThatThrownBy(() ->
                trustedChatMessageFactory.createUserChatMessage(request, ROOM_ID, USER_IDENTIFIER)
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("일반 채팅 경로로 시스템 메시지 타입을 위조할 수 없다")
    void createUserChatMessage_failsWhenMessageTypeSpoofed() {
        // given
        ChatMessageDto request = ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.KICK)
                .content("강퇴 메시지 위조")
                .build();

        // when & then
        assertThatThrownBy(() ->
                trustedChatMessageFactory.createUserChatMessage(request, ROOM_ID, USER_IDENTIFIER)
        )
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }

    @Test
    @DisplayName("타입이 null이면 기존 FE 호환성을 위해 CHAT으로 처리한다")
    void createUserChatMessage_successWhenTypeNull() {
        // given
        ChatMessageDto request = ChatMessageDto.builder()
                .type(null)
                .content("안녕하세요")
                .build();

        when(chatMessageIdGenerator.generate()).thenReturn(MESSAGE_ID);
        when(userNicknameLookupService.findProfileMapByUserIdentifiers(List.of(USER_IDENTIFIER)))
                .thenReturn(Map.of(
                        USER_IDENTIFIER,
                        new UserIdentifierProfile(USER_ID, NICKNAME)
                ));

        // when
        ChatMessageDto result = trustedChatMessageFactory.createUserChatMessage(
                request,
                ROOM_ID,
                USER_IDENTIFIER
        );

        // then
        assertThat(result.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.getType()).isEqualTo(ChatMessageDto.MessageType.CHAT);
        assertThat(result.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(result.getSender()).isEqualTo(USER_IDENTIFIER);
        assertThat(result.getSenderId()).isEqualTo(USER_ID);
        assertThat(result.getSenderNickname()).isEqualTo(NICKNAME);
        assertThat(result.getContent()).isEqualTo("안녕하세요");
        assertUtcTimestamp(result.getTimestamp());
        assertUtcTimestamp(result.getSentAt());
    }

    private ChatMessageDto chatMessage(String content) {
        return ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .content(content)
                .build();
    }

    private void assertUtcTimestamp(String timestamp) {
        assertThat(timestamp).isNotBlank();
        assertThat(timestamp).endsWith("Z");
        assertThatCode(() -> Instant.parse(timestamp))
                .doesNotThrowAnyException();
    }
}