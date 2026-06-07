package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.ReapLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LobbyReaperSchedulerTest {

    private static final long GRACE_MS = 120_000L;

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier = mock(LobbyRealtimeNotifier.class);
    private final LobbyReaperMetric lobbyReaperMetric = mock(LobbyReaperMetric.class);

    private final LobbyReaperScheduler scheduler = new LobbyReaperScheduler(
            lobbyRepository,
            eventPublisher,
            lobbyRealtimeNotifier,
            lobbyReaperMetric,
            GRACE_MS
    );

    @Test
    @DisplayName("여러 로비를 폭파해도 목록 refresh는 배치 종료 후 1회만 발행하고, 폭파된 로비마다 LobbyClosedEvent를 발행한다")
    void reapsMultipleLobbiesButRefreshesListOnce() {
        // given
        when(lobbyRepository.getAllLobbyCodesForReaping(anyInt()))
                .thenReturn(List.of("DEAD01", "DEAD02", "ALIVE1"));
        when(lobbyRepository.reapEmptyLobby("DEAD01", GRACE_MS)).thenReturn(ReapLobbyResult.REAPED);
        when(lobbyRepository.reapEmptyLobby("DEAD02", GRACE_MS)).thenReturn(ReapLobbyResult.REAPED);
        when(lobbyRepository.reapEmptyLobby("ALIVE1", GRACE_MS)).thenReturn(ReapLobbyResult.ALIVE);

        // when
        scheduler.reapEmptyLobbies();

        // then
        verify(eventPublisher).publishEvent(new LobbyClosedEvent("DEAD01"));
        verify(eventPublisher).publishEvent(new LobbyClosedEvent("DEAD02"));
        verify(eventPublisher, never()).publishEvent(new LobbyClosedEvent("ALIVE1"));

        verify(lobbyRealtimeNotifier, times(1)).notifyLobbyListRefresh();

        verify(lobbyReaperMetric, times(2)).incrementReaped();
        verify(lobbyReaperMetric).incrementScanned(3L);
    }

    @Test
    @DisplayName("ERROR 결과는 error 메트릭을 증가시키고 LobbyClosedEvent를 발행하지 않는다")
    void countsErrorResultWithoutPublishingEvent() {
        // given
        when(lobbyRepository.getAllLobbyCodesForReaping(anyInt()))
                .thenReturn(List.of("ERR001"));
        when(lobbyRepository.reapEmptyLobby("ERR001", GRACE_MS)).thenReturn(ReapLobbyResult.ERROR);

        // when
        scheduler.reapEmptyLobbies();

        // then
        verify(lobbyReaperMetric).incrementError();
        verify(eventPublisher, never()).publishEvent(eq(new LobbyClosedEvent("ERR001")));
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
    }

    @Test
    @DisplayName("폭파된 로비가 없으면 목록 refresh를 발행하지 않는다")
    void doesNotRefreshWhenNothingReaped() {
        // given
        when(lobbyRepository.getAllLobbyCodesForReaping(anyInt()))
                .thenReturn(List.of("YOUNG1", "ALIVE1"));
        when(lobbyRepository.reapEmptyLobby("YOUNG1", GRACE_MS)).thenReturn(ReapLobbyResult.TOO_YOUNG);
        when(lobbyRepository.reapEmptyLobby("ALIVE1", GRACE_MS)).thenReturn(ReapLobbyResult.ALIVE);

        // when
        scheduler.reapEmptyLobbies();

        // then
        verify(lobbyRealtimeNotifier, never()).notifyLobbyListRefresh();
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
        verify(lobbyReaperMetric).incrementScanned(2L);
    }

    @Test
    @DisplayName("후보가 없으면 아무 동작도 하지 않는다")
    void doesNothingWhenNoCandidates() {
        // given
        when(lobbyRepository.getAllLobbyCodesForReaping(anyInt())).thenReturn(List.of());

        // when
        scheduler.reapEmptyLobbies();

        // then
        verify(lobbyRepository, never()).reapEmptyLobby(org.mockito.ArgumentMatchers.anyString(), anyLong());
        verifyNoInteractions(eventPublisher, lobbyRealtimeNotifier, lobbyReaperMetric);
    }
}
