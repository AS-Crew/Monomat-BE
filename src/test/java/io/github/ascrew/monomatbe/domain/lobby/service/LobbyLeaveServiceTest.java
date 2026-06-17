package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyLeaveServiceTest {

    private static final String LOBBY_CODE = "TEST94";
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_HOST_IDENTIFIER = "22222222-2222-2222-2222-222222222222";
    private static final String WS_SESSION_ID = "ws-session-1";

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier = mock(LobbyRealtimeNotifier.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

    private final LobbyLeaveService service = new LobbyLeaveService(
            lobbyRepository,
            lobbyRealtimeNotifier,
            eventPublisher,
            stringRedisTemplate
    );

    // =========================================================
    // processLeave 결과 분기
    // =========================================================

    @Test
    @DisplayName("일반 참가자 퇴장 결과이면 로비 내부 refresh를 발행한다")
    void notifyLobbyInfoRefreshWhenParticipantLeft() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Left(LOBBY_CODE, USER_IDENTIFIER));

        // when
        service.processLeave(LOBBY_CODE, USER_IDENTIFIER);

        // then
        verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
    }

    @Test
    @DisplayName("방장 위임 결과이면 HOST_CHANGED 메시지와 로비 내부 refresh를 발행한다")
    void notifyHostChangedAndRefreshWhenHostDelegated() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Delegated(LOBBY_CODE, NEW_HOST_IDENTIFIER));

        // when
        service.processLeave(LOBBY_CODE, USER_IDENTIFIER);

        // then
        verify(lobbyRealtimeNotifier).notifyHostChangedMessage(LOBBY_CODE, NEW_HOST_IDENTIFIER);
        verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
    }

    @Test
    @DisplayName("마지막 유저 퇴장으로 로비가 삭제되면 로비 목록 refresh와 LobbyClosedEvent를 발행한다")
    void notifyLobbyListRefreshAndPublishClosedEventWhenLobbyDestroyed() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Destroyed(LOBBY_CODE));

        // when
        service.processLeave(LOBBY_CODE, USER_IDENTIFIER);

        // then
        verify(lobbyRealtimeNotifier).notifyLobbyListRefresh();
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(eventPublisher).publishEvent(new LobbyClosedEvent(LOBBY_CODE));
    }

    @Test
    @DisplayName("퇴장 처리 실패 결과이면 refresh를 발행하지 않는다")
    void doesNotNotifyWhenLeaveProcessFailed() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Error("Redis Lua execution failed"));

        // when
        service.processLeave(LOBBY_CODE, USER_IDENTIFIER);

        // then
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
        verify(lobbyRealtimeNotifier, never()).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(eventPublisher, never()).publishEvent(new LobbyClosedEvent(LOBBY_CODE));
    }

    // =========================================================
    // leaveByRequest 명시적 퇴장
    // =========================================================

    @Test
    @DisplayName("명시적 퇴장(Left)이면 LEAVE 메시지를 발행하고 ws:connection 키를 정리한다")
    void publishLeaveMessageAndCleanupWsConnectionWhenLeft() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Left(LOBBY_CODE, USER_IDENTIFIER));

        // when
        service.leaveByRequest(LOBBY_CODE, USER_IDENTIFIER, WS_SESSION_ID);

        // then
        verify(lobbyRealtimeNotifier).notifyLobbyInfoRefresh(LOBBY_CODE);
        verify(lobbyRealtimeNotifier).notifyLeaveMessage(LOBBY_CODE, USER_IDENTIFIER);
        verify(stringRedisTemplate).delete(RedisKeys.wsConnectionKey(WS_SESSION_ID));
    }

    @Test
    @DisplayName("명시적 퇴장으로 로비가 폭파되면 LEAVE 메시지는 발행하지 않고 ws:connection 키만 정리한다")
    void doesNotPublishLeaveMessageWhenDestroyed() {
        // given
        when(lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, USER_IDENTIFIER))
                .thenReturn(new LeaveLobbyResult.Destroyed(LOBBY_CODE));

        // when
        service.leaveByRequest(LOBBY_CODE, USER_IDENTIFIER, WS_SESSION_ID);

        // then
        verify(lobbyRealtimeNotifier, never()).notifyLeaveMessage(LOBBY_CODE, USER_IDENTIFIER);
        verify(stringRedisTemplate).delete(RedisKeys.wsConnectionKey(WS_SESSION_ID));
    }

    @Test
    @DisplayName("유효하지 않은 로비 코드면 퇴장 처리를 실행하지 않는다")
    void doesNotProcessWhenLobbyCodeIsInvalid() {
        // when
        service.leaveByRequest("bad-code!", USER_IDENTIFIER, WS_SESSION_ID);

        // then
        verify(lobbyRepository, never()).executeLeaveLobbyProcess("bad-code!", USER_IDENTIFIER);
        verify(stringRedisTemplate, never()).delete(RedisKeys.wsConnectionKey(WS_SESSION_ID));
    }

    @Test
    @DisplayName("식별자가 없으면 퇴장 처리를 실행하지 않는다")
    void doesNotProcessWhenUserIdentifierIsBlank() {
        // when
        service.leaveByRequest(LOBBY_CODE, " ", WS_SESSION_ID);

        // then
        verify(lobbyRepository, never()).executeLeaveLobbyProcess(LOBBY_CODE, " ");
        verify(stringRedisTemplate, never()).delete(RedisKeys.wsConnectionKey(WS_SESSION_ID));
    }
}
