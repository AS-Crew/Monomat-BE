package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyLeaveEventHandlerTest {

    private static final String LOBBY_CODE = "TEST94";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_HOST_IDENTIFIER = "22222222-2222-2222-2222-222222222222";

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier = mock(LobbyRealtimeNotifier.class);

    private final LobbyLeaveEventHandler handler = new LobbyLeaveEventHandler(
            lobbyRepository,
            lobbyRealtimeNotifier
    );

    @Test
    @DisplayName("PlayerLeaveEvent 수신 시 로비 퇴장 처리를 실행한다")
    void executeLeaveProcessWhenPlayerLeaveEventReceived() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Left(LOBBY_CODE, USER_IDENTIFIER));

        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRepository).executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER);
    }

    @Test
    @DisplayName("일반 참가자 퇴장 결과이면 로비 내부 refresh를 발행한다")
    void notifyLobbyInfoRefreshWhenParticipantLeft() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Left(LOBBY_CODE, USER_IDENTIFIER));

        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
    }

    @Test
    @DisplayName("방장 위임 결과이면 로비 내부 refresh를 발행한다")
    void notifyLobbyInfoRefreshWhenHostDelegated() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Delegated(LOBBY_CODE, NEW_HOST_IDENTIFIER));

        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
    }

    @Test
    @DisplayName("마지막 유저 퇴장으로 로비가 삭제되면 로비 목록 refresh를 발행한다")
    void notifyLobbyListRefreshWhenLobbyDestroyed() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Destroyed(LOBBY_CODE));

        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRealtimeNotifier).notifyLobbyListRefresh();
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(LOBBY_CODE);
    }

    @Test
    @DisplayName("퇴장 처리 실패 결과이면 refresh를 발행하지 않는다")
    void doesNotNotifyWhenLeaveProcessFailed() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Error("Redis Lua execution failed"));

        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(LOBBY_CODE);
    }

    @Test
    @DisplayName("로비 코드가 없으면 퇴장 처리를 실행하지 않는다")
    void doesNotExecuteLeaveProcessWhenLobbyCodeIsBlank() {
        // given
        PlayerLeaveEvent event = new PlayerLeaveEvent(" ", USER_IDENTIFIER);

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRepository, never()).executeLeaveLobbyProcess(" ", USER_IDENTIFIER);
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
    }

    @Test
    @DisplayName("사용자 식별자가 없으면 퇴장 처리를 실행하지 않는다")
    void doesNotExecuteLeaveProcessWhenUserIdentifierIsBlank() {
        // given
        PlayerLeaveEvent event = new PlayerLeaveEvent(LOBBY_CODE, " ");

        // when
        handler.handlePlayerLeave(event);

        // then
        verify(lobbyRepository, never()).executeLeaveLobbyProcess(LOBBY_CODE, " ");
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(LOBBY_CODE);
    }
}