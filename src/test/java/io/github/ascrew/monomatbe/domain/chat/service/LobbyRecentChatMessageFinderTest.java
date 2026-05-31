package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LobbyRecentChatMessageFinderTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_MESSAGE_ID = "33333333-3333-3333-3333-333333333333";
    private static final String RECENT_CHAT_KEY = RedisKeys.lobbyRecentChatMessagesKey(LOBBY_CODE);

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    private final JsonMapper jsonMapper = new JsonMapper();

    private LobbyRecentChatMessageFinder finder;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        finder = new LobbyRecentChatMessageFinder(
                redisTemplate,
                jsonMapper
        );
    }

    @Test
    @DisplayName("messageId에 해당하는 최근 채팅 메시지를 찾는다")
    void findByMessageId_success() throws Exception {
        // given
        ChatMessageDto targetMessage = chatMessage(MESSAGE_ID, "신고 대상 메시지");
        ChatMessageDto otherMessage = chatMessage(OTHER_MESSAGE_ID, "다른 메시지");

        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenReturn(List.of(
                        jsonMapper.writeValueAsString(otherMessage),
                        jsonMapper.writeValueAsString(targetMessage)
                ));

        // when
        Optional<ChatMessageDto> result = finder.findByMessageId(LOBBY_CODE, MESSAGE_ID);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(result.get().getContent()).isEqualTo("신고 대상 메시지");
    }

    @Test
    @DisplayName("messageId에 해당하는 메시지가 없으면 Optional.empty를 반환한다")
    void findByMessageId_returnsEmptyWhenMessageNotFound() throws Exception {
        // given
        ChatMessageDto otherMessage = chatMessage(OTHER_MESSAGE_ID, "다른 메시지");

        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenReturn(List.of(jsonMapper.writeValueAsString(otherMessage)));

        // when
        Optional<ChatMessageDto> result = finder.findByMessageId(LOBBY_CODE, MESSAGE_ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("최근 채팅 목록이 비어 있으면 Optional.empty를 반환한다")
    void findByMessageId_returnsEmptyWhenRecentChatEmpty() {
        // given
        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenReturn(List.of());

        // when
        Optional<ChatMessageDto> result = finder.findByMessageId(LOBBY_CODE, MESSAGE_ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("개별 payload 역직렬화에 실패하면 해당 payload만 건너뛴다")
    void findByMessageId_skipsInvalidPayload() throws Exception {
        // given
        ChatMessageDto targetMessage = chatMessage(MESSAGE_ID, "신고 대상 메시지");

        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenReturn(List.of(
                        "{ invalid-json",
                        jsonMapper.writeValueAsString(targetMessage)
                ));

        // when
        Optional<ChatMessageDto> result = finder.findByMessageId(LOBBY_CODE, MESSAGE_ID);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getMessageId()).isEqualTo(MESSAGE_ID);
    }

    @Test
    @DisplayName("Redis 조회 실패는 RecentChatMessageLookupException으로 변환한다")
    void findByMessageId_throwsWhenRedisLookupFails() {
        // given
        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // when & then
        assertThatThrownBy(() -> finder.findByMessageId(LOBBY_CODE, MESSAGE_ID))
                .isInstanceOf(RecentChatMessageLookupException.class)
                .hasMessage("로비 최근 채팅 저장소를 조회할 수 없습니다.");
    }

    @Test
    @DisplayName("lobbyCode 또는 messageId가 비어 있으면 Redis를 조회하지 않고 Optional.empty를 반환한다")
    void findByMessageId_returnsEmptyWhenArgumentsInvalid() {
        assertThat(finder.findByMessageId(null, MESSAGE_ID)).isEmpty();
        assertThat(finder.findByMessageId(" ", MESSAGE_ID)).isEmpty();
        assertThat(finder.findByMessageId(LOBBY_CODE, null)).isEmpty();
        assertThat(finder.findByMessageId(LOBBY_CODE, " ")).isEmpty();
    }

    private ChatMessageDto chatMessage(String messageId, String content) {
        return ChatMessageDto.builder()
                .messageId(messageId)
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(LOBBY_CODE)
                .sender("sender-identifier")
                .senderId(2L)
                .senderNickname("신고대상")
                .content(content)
                .timestamp("2026-05-30T12:00:00.123Z")
                .sentAt("2026-05-30T12:00:00.123Z")
                .build();
    }
}