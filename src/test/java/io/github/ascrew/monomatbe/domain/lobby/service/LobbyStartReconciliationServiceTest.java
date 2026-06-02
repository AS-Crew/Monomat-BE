package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LobbyStartReconciliationService 재처리 워커 로직 검증.
 *
 * 큐에서 leftPop으로 꺼낸 payload는 처리 중 예외가 나면 유실될 수 있으므로,
 * 최상위 보상 가드가 원본 payload를 안전하게 재적재하는지 단위 검증한다.
 */
class LobbyStartReconciliationServiceTest {

    private static final String CODE = "ABC123";
    private static final String REASON = "START_DB_SYNC_FAILED";

    private final LobbyRepository lobbyRepository = mock(LobbyRepository.class);

    private final LobbyStartReconciliationService service =
            new LobbyStartReconciliationService(lobbyRepository);

    private String payload(int attempt, long nextRetryAt) {
        return String.join("|", CODE, REASON, String.valueOf(attempt), String.valueOf(nextRetryAt));
    }

    @Test
    @DisplayName("처리 중 예외가 발생하면 실패 집계 후 원본 payload를 안전 재적재한다 (payload 유실 방지)")
    void processingThrows_safeRequeuesOriginalPayload() {
        // not-due 경로의 requeue가 Redis 장애로 실패하는 상황을 모사한다.
        String notDue = payload(0, System.currentTimeMillis() + 60_000L);
        when(lobbyRepository.pollStartReconciliation()).thenReturn(notDue);
        doThrow(new RuntimeException("redis down")).when(lobbyRepository).requeueStartReconciliation(notDue);

        service.reconcileStartState();

        verify(lobbyRepository).incrementStartReconciliationMetric(RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED);
        verify(lobbyRepository).safeRequeueStartReconciliation(notDue);
    }

    @Test
    @DisplayName("아직 점검 시각 전이면 정상적으로 큐 뒤로 재적재하며 보상 경로를 타지 않는다")
    void notDueYet_requeuesWithoutCompensation() {
        String notDue = payload(0, System.currentTimeMillis() + 60_000L);
        when(lobbyRepository.pollStartReconciliation()).thenReturn(notDue);

        service.reconcileStartState();

        verify(lobbyRepository).requeueStartReconciliation(notDue);
        verify(lobbyRepository, never()).safeRequeueStartReconciliation(notDue);
    }

    @Test
    @DisplayName("큐가 비어 있으면 아무 작업도 하지 않는다")
    void emptyQueue_isNoOp() {
        when(lobbyRepository.pollStartReconciliation()).thenReturn(null);

        service.reconcileStartState();

        verify(lobbyRepository, never()).requeueStartReconciliation(org.mockito.ArgumentMatchers.anyString());
        verify(lobbyRepository, never()).safeRequeueStartReconciliation(org.mockito.ArgumentMatchers.anyString());
    }
}
