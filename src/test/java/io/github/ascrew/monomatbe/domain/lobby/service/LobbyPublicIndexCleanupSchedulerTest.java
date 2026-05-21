package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyPublicIndexCleanupSchedulerTest {

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);

    private final LobbyPublicIndexCleanupScheduler scheduler =
            new LobbyPublicIndexCleanupScheduler(lobbyRepository);

    @Test
    @DisplayName("공개 로비 인덱스 정리 시 Hash가 없는 stale code만 제거한다")
    void cleanupStalePublicLobbyIndexes_removesOnlyStaleCodes() {
        // given
        when(lobbyRepository.getPublicLobbyCodesForCleanup(100))
                .thenReturn(List.of("VALID01", "STALE01", "STALE02"));

        when(lobbyRepository.existsByCode("VALID01")).thenReturn(true);
        when(lobbyRepository.existsByCode("STALE01")).thenReturn(false);
        when(lobbyRepository.existsByCode("STALE02")).thenReturn(false);

        // when
        scheduler.cleanupStalePublicLobbyIndexes();

        // then
        verify(lobbyRepository, never()).removePublicLobbyIndexes("VALID01");
        verify(lobbyRepository).removePublicLobbyIndexes("STALE01");
        verify(lobbyRepository).removePublicLobbyIndexes("STALE02");
    }

    @Test
    @DisplayName("정리 후보가 없으면 아무 인덱스도 제거하지 않는다")
    void cleanupStalePublicLobbyIndexes_doesNothingWhenCandidatesAreEmpty() {
        // given
        when(lobbyRepository.getPublicLobbyCodesForCleanup(100))
                .thenReturn(List.of());

        // when
        scheduler.cleanupStalePublicLobbyIndexes();

        // then
        verify(lobbyRepository, never()).removePublicLobbyIndexes(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("blank code는 제거 대상에서 제외한다")
    void cleanupStalePublicLobbyIndexes_ignoresBlankCodes() {
        // given
        when(lobbyRepository.getPublicLobbyCodesForCleanup(100))
                .thenReturn(List.of("", "   ", "STALE01"));

        when(lobbyRepository.existsByCode("STALE01")).thenReturn(false);

        // when
        scheduler.cleanupStalePublicLobbyIndexes();

        // then
        verify(lobbyRepository, never()).removePublicLobbyIndexes("");
        verify(lobbyRepository, never()).removePublicLobbyIndexes("   ");
        verify(lobbyRepository).removePublicLobbyIndexes("STALE01");
    }
}