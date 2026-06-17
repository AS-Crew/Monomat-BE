package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LobbyLeaveEventHandlerTest {

    private static final String LOBBY_CODE = "TEST94";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";

    private final LobbyLeaveService lobbyLeaveService = mock(LobbyLeaveService.class);

    private final LobbyLeaveEventHandler handler = new LobbyLeaveEventHandler(lobbyLeaveService);

    @Test
    @DisplayName("PlayerLeaveEvent 수신 시 퇴장 처리를 LobbyLeaveService에 위임한다")
    void delegatesToLobbyLeaveServiceWhenPlayerLeaveEventReceived() {
        // given
        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyLeaveService).processLeave(LOBBY_CODE, USER_IDENTIFIER);
    }

    @Test
    @DisplayName("로비 코드가 없으면 퇴장 처리를 위임하지 않는다")
    void doesNotDelegateWhenLobbyCodeIsBlank() {
        // given
        PlayerLeaveEvent event = new PlayerLeaveEvent(" ", USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyLeaveService, never()).processLeave(" ", USER_IDENTIFIER);
    }

    @Test
    @DisplayName("사용자 식별자가 없으면 퇴장 처리를 위임하지 않는다")
    void doesNotDelegateWhenUserIdentifierIsBlank() {
        // given
        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, " ");

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyLeaveService, never()).processLeave(LOBBY_CODE, " ");
    }
}
