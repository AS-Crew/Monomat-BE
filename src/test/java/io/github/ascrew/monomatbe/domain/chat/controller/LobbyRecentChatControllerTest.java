package io.github.ascrew.monomatbe.domain.chat.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.chat.service.LobbyRecentChatQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyRecentChatControllerTest {

    private static final String LOBBY_CODE = "ABC123";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";

    private final LobbyRecentChatQueryService lobbyRecentChatQueryService =
            mock(LobbyRecentChatQueryService.class);

    private final LobbyRecentChatController controller =
            new LobbyRecentChatController(lobbyRecentChatQueryService);

    @Test
    @DisplayName("인증된 로비 참여자는 최근 채팅 목록을 조회할 수 있다")
    void getRecentLobbyChats_success() {
        // given
        CustomPrincipal principal = new CustomPrincipal(
                1L,
                USER_IDENTIFIER,
                UserType.GUEST
        );

        List<ChatMessageDto> recentMessages = List.of(
                ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.CHAT)
                        .roomId(LOBBY_CODE)
                        .sender(USER_IDENTIFIER)
                        .content("안녕하세요")
                        .timestamp("2026-05-30T12:00:00")
                        .build()
        );

        when(lobbyRecentChatQueryService.getRecentMessages(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(recentMessages);

        // when
        ResponseEntity<List<ChatMessageDto>> response =
                controller.getRecentLobbyChats(LOBBY_CODE, principal);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(recentMessages);

        verify(lobbyRecentChatQueryService).getRecentMessages(
                LOBBY_CODE,
                USER_IDENTIFIER
        );
    }

    @Test
    @DisplayName("인증 주체가 없으면 최근 채팅을 조회할 수 없다")
    void getRecentLobbyChats_failsWhenPrincipalMissing() {
        // when & then
        assertThatThrownBy(() -> controller.getRecentLobbyChats(LOBBY_CODE, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }

    @Test
    @DisplayName("인증 주체의 userIdentifier가 없으면 최근 채팅을 조회할 수 없다")
    void getRecentLobbyChats_failsWhenUserIdentifierMissing() {
        // given
        CustomPrincipal principal = new CustomPrincipal(
                1L,
                null,
                UserType.GUEST
        );

        // when & then
        assertThatThrownBy(() -> controller.getRecentLobbyChats(LOBBY_CODE, principal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }
}