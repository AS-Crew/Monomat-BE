package io.github.ascrew.monomatbe.domain.chat.service;

import io.github.ascrew.monomatbe.domain.lobby.LobbyUserAccessStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyRecentChatQueryServiceTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String RECENT_CHAT_KEY = RedisKeys.lobbyRecentChatMessagesKey(LOBBY_CODE);

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private LobbyRepository lobbyRepository;
    private LobbyRecentChatQueryService service;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        lobbyRepository = mock(LobbyRepository.class);
        jsonMapper = JsonMapper.builder().build();

        service = new LobbyRecentChatQueryService(
                redisTemplate,
                jsonMapper,
                lobbyRepository
        );
    }

    @Test
    @DisplayName("로비 참여자는 최근 채팅 목록을 오래된 메시지부터 조회할 수 있다")
    void getRecentMessages_success() {
        // given
        givenReadableParticipant();

        ChatMessageDto firstMessage = chatMessage("첫 번째 메시지", "2026-05-30T12:00:00.000Z");
        ChatMessageDto secondMessage = chatMessage("두 번째 메시지", "2026-05-30T12:00:01.000Z");

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenReturn(List.of(
                        jsonMapper.writeValueAsString(firstMessage),
                        jsonMapper.writeValueAsString(secondMessage)
                ));

        // when
        List<ChatMessageDto> result = service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).isEqualTo("첫 번째 메시지");
        assertThat(result.get(1).getContent()).isEqualTo("두 번째 메시지");

        verify(listOperations).range(RECENT_CHAT_KEY, 0, -1);
    }

    @Test
    @DisplayName("로비가 존재하지 않으면 최근 채팅을 조회할 수 없다")
    void getRecentMessages_failsWhenLobbyNotFound() {
        // given
        when(lobbyRepository.getUserAccessStatus(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.LOBBY_NOT_FOUND);

        // when & then
        assertThatThrownBy(() -> service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
                );

        verify(redisTemplate, never()).opsForList();
    }

    @Test
    @DisplayName("강퇴된 유저는 최근 채팅을 조회할 수 없다")
    void getRecentMessages_failsWhenUserKicked() {
        // given
        when(lobbyRepository.getUserAccessStatus(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.KICKED);

        // when & then
        assertThatThrownBy(() -> service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                );

        verify(redisTemplate, never()).opsForList();
    }

    @Test
    @DisplayName("로비 참여자가 아니면 최근 채팅을 조회할 수 없다")
    void getRecentMessages_failsWhenNotParticipant() {
        // given
        when(lobbyRepository.getUserAccessStatus(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.NOT_PARTICIPANT);

        // when & then
        assertThatThrownBy(() -> service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                );

        verify(redisTemplate, never()).opsForList();
    }

    @Test
    @DisplayName("Redis에 최근 채팅이 없으면 빈 목록을 반환한다")
    void getRecentMessages_returnsEmptyListWhenNoMessages() {
        // given
        givenReadableParticipant();

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(RECENT_CHAT_KEY, 0, -1)).thenReturn(List.of());

        // when
        List<ChatMessageDto> result = service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Redis 조회 실패 시 빈 목록을 반환한다")
    void getRecentMessages_returnsEmptyListWhenRedisUnavailable() {
        // given
        givenReadableParticipant();

        when(redisTemplate.opsForList()).thenThrow(new RuntimeException("Redis unavailable"));

        // when
        List<ChatMessageDto> result = service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("깨진 JSON payload는 건너뛰고 정상 메시지만 반환한다")
    void getRecentMessages_skipsBrokenPayload() {
        // given
        givenReadableParticipant();

        ChatMessageDto validMessage = chatMessage("정상 메시지", "2026-05-30T12:00:00.000Z");

        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(RECENT_CHAT_KEY, 0, -1))
                .thenReturn(List.of(
                        "{ broken json",
                        jsonMapper.writeValueAsString(validMessage)
                ));

        // when
        List<ChatMessageDto> result = service.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("정상 메시지");
    }

    private void givenReadableParticipant() {
        when(lobbyRepository.getUserAccessStatus(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
    }

    private ChatMessageDto chatMessage(String content, String timestamp) {
        return ChatMessageDto.builder()
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(LOBBY_CODE)
                .sender(USER_IDENTIFIER)
                .content(content)
                .timestamp(timestamp)
                .build();
    }
}