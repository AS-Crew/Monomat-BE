package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.chat.config.LobbyRecentChatProperties;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyRecentChatStoreServiceTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String RECENT_CHAT_KEY = RedisKeys.lobbyRecentChatMessagesKey(LOBBY_CODE);

    private StringRedisTemplate redisTemplate;
    private RedisScript<String> appendRecentLobbyChatScript;
    private LobbyRecentChatProperties properties;
    private LobbyRecentChatStoreService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        appendRecentLobbyChatScript = mock(RedisScript.class);

        properties = new LobbyRecentChatProperties();
        properties.setMaxSize(50);
        properties.setTtl(Duration.ofHours(2));

        service = new LobbyRecentChatStoreService(
                redisTemplate,
                JsonMapper.builder().build(),
                appendRecentLobbyChatScript,
                properties
        );
    }

    @Test
    @DisplayName("로비 최근 채팅 저장 시 Lua 스크립트로 append, trim, expire를 원자 처리한다")
    void append_success() {
        // given
        ChatMessageDto message = chatMessage("안녕하세요");

        when(redisTemplate.execute(
                eq(appendRecentLobbyChatScript),
                eq(List.of(RECENT_CHAT_KEY)),
                any(String.class),
                eq("50"),
                eq("7200")
        )).thenReturn("OK");

        // when
        service.append(LOBBY_CODE, message);

        // then
        verify(redisTemplate).execute(
                eq(appendRecentLobbyChatScript),
                eq(List.of(RECENT_CHAT_KEY)),
                any(String.class),
                eq("50"),
                eq("7200")
        );
    }

    @Test
    @DisplayName("로비 최근 채팅 저장 Lua 결과가 OK가 아니어도 예외를 전파하지 않는다")
    void append_doesNotThrowWhenLuaReturnsError() {
        // given
        ChatMessageDto message = chatMessage("안녕하세요");

        when(redisTemplate.execute(
                eq(appendRecentLobbyChatScript),
                eq(List.of(RECENT_CHAT_KEY)),
                any(String.class),
                eq("50"),
                eq("7200")
        )).thenReturn("ERROR_INVALID_TTL");

        // when & then
        assertThatCode(() -> service.append(LOBBY_CODE, message))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 저장 실패 시 예외를 전파하지 않는다")
    void append_doesNotThrowWhenRedisFails() {
        // given
        ChatMessageDto message = chatMessage("안녕하세요");

        when(redisTemplate.execute(
                eq(appendRecentLobbyChatScript),
                eq(List.of(RECENT_CHAT_KEY)),
                any(String.class),
                eq("50"),
                eq("7200")
        )).thenThrow(new RuntimeException("Redis unavailable"));

        // when & then
        assertThatCode(() -> service.append(LOBBY_CODE, message))
                .doesNotThrowAnyException();
    }

    private ChatMessageDto chatMessage(String content) {
        return ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(LOBBY_CODE)
                .sender(USER_IDENTIFIER)
                .content(content)
                .timestamp("2026-05-30T12:00:00.000Z")
                .build();
    }
}