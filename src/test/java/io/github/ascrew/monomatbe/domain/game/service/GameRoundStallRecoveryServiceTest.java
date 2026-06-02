package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.repository.redis.GameRoundRecoveryRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameRoundStallRecoveryService 복구 워커 로직 검증.
 *
 * 실제 Redis/스케줄러 없이 큐 payload 분기(미도래/이미 진행/세션 소멸/정지 재트리거/실패 백오프)를 단위 검증한다.
 */
class GameRoundStallRecoveryServiceTest {

    private static final String CODE = "ABC123";
    private static final String SESSION_KEY = RedisKeys.gameSessionKey(CODE);

    private final GameRoundRecoveryRepository repository = mock(GameRoundRecoveryRepository.class);
    private final GameRoundNextRoundExecutor nextRoundExecutor = mock(GameRoundNextRoundExecutor.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);

    private final GameRoundStallRecoveryService service = new GameRoundStallRecoveryService(
            repository, nextRoundExecutor, redisTemplate);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private String payload(int expectedRoundNo, int attempt, long nextRetryAt) {
        return String.join("|", CODE, String.valueOf(expectedRoundNo), String.valueOf(attempt), String.valueOf(nextRetryAt));
    }

    /** inspectProgress가 single-RTT multiGet([current_round_no, status])으로 조회하는 값을 스텁한다. */
    private void stubSessionFields(String currentRoundNo, String status) {
        when(hashOperations.multiGet(eq(SESSION_KEY), any()))
                .thenReturn(Arrays.asList(currentRoundNo, status));
    }

    @Test
    @DisplayName("아직 점검 시각 전이면 상태 점검 없이 큐 뒤로 재적재한다")
    void notDueYet_requeuesWithoutInspecting() {
        String notDue = payload(3, 0, System.currentTimeMillis() + 60_000L);
        when(repository.pollRoundRecovery()).thenReturn(notDue);

        service.recoverStalledRounds();

        verify(repository).requeueRoundRecovery(notDue);
        verify(nextRoundExecutor, never()).startNextRound(anyString(), anyInt());
        verify(hashOperations, never()).multiGet(any(), any());
    }

    @Test
    @DisplayName("current_round_no가 기대치 이상이면 이미 진행됨으로 보고 성공 집계 후 드롭한다")
    void alreadyProgressed_marksSuccessAndDrops() {
        when(repository.pollRoundRecovery()).thenReturn(payload(3, 0, pastTime()));
        stubSessionFields("3", "READY");

        service.recoverStalledRounds();

        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_SUCCESS);
        verify(nextRoundExecutor, never()).startNextRound(anyString(), anyInt());
        verify(repository, never()).requeueRoundRecovery(anyString());
    }

    @Test
    @DisplayName("세션 해시가 없으면(정리/만료) 복구 불필요로 보고 성공 집계 후 드롭한다")
    void sessionGone_marksSuccessAndDrops() {
        when(repository.pollRoundRecovery()).thenReturn(payload(3, 0, pastTime()));
        stubSessionFields(null, null);

        service.recoverStalledRounds();

        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_SUCCESS);
        verify(nextRoundExecutor, never()).startNextRound(anyString(), anyInt());
    }

    @Test
    @DisplayName("정지된 라운드(current_round_no < 기대치)는 startNextRound를 재트리거하고 성공 집계한다")
    void stalled_retriggersStartNextRound() {
        when(repository.pollRoundRecovery()).thenReturn(payload(3, 0, pastTime()));
        stubSessionFields("2", "PLAYING");

        service.recoverStalledRounds();

        verify(nextRoundExecutor).startNextRound(CODE, 3);
        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_SUCCESS);
        verify(repository, never()).requeueRoundRecovery(anyString());
    }

    @Test
    @DisplayName("재트리거가 실패하면 실패 집계 후 backoff payload로 재적재한다")
    void retriggerFailure_marksFailedAndRequeues() {
        when(repository.pollRoundRecovery()).thenReturn(payload(3, 0, pastTime()));
        stubSessionFields("2", "PLAYING");
        doThrow(new RuntimeException("db down")).when(nextRoundExecutor).startNextRound(CODE, 3);

        service.recoverStalledRounds();

        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
        verify(repository).requeueRoundRecovery(anyString());
    }

    @Test
    @DisplayName("최대 재시도 횟수를 초과하면 재적재하지 않는다")
    void exceedsMaxRetry_doesNotRequeue() {
        when(repository.pollRoundRecovery()).thenReturn(payload(3, 5, pastTime()));
        stubSessionFields("2", "PLAYING");
        doThrow(new RuntimeException("db down")).when(nextRoundExecutor).startNextRound(CODE, 3);

        service.recoverStalledRounds();

        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
        verify(repository, never()).requeueRoundRecovery(anyString());
    }

    @Test
    @DisplayName("처리 중 예외가 발생하면 실패 집계 후 원본 payload를 안전 재적재한다 (payload 유실 방지)")
    void processingThrows_safeRequeuesOriginalPayload() {
        // not-due 경로의 requeue가 Redis 장애로 실패하는 상황을 모사한다.
        String notDue = payload(3, 0, System.currentTimeMillis() + 60_000L);
        when(repository.pollRoundRecovery()).thenReturn(notDue);
        doThrow(new RuntimeException("redis down")).when(repository).requeueRoundRecovery(notDue);

        service.recoverStalledRounds();

        // 최상위 보상: 실패 집계 + 원본 payload 안전 재적재
        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
        verify(repository).safeRequeueRoundRecovery(notDue);
    }

    @Test
    @DisplayName("큐가 비어 있으면 아무 작업도 하지 않는다")
    void emptyQueue_isNoOp() {
        when(repository.pollRoundRecovery()).thenReturn(null);

        service.recoverStalledRounds();

        verify(nextRoundExecutor, never()).startNextRound(anyString(), anyInt());
        verify(repository, never()).incrementRoundRecoveryMetric(anyString());
        verify(repository, never()).requeueRoundRecovery(anyString());
    }

    @Test
    @DisplayName("payload 형식이 잘못되면 실패 집계만 하고 재적재하지 않는다")
    void malformedPayload_marksFailedOnly() {
        when(repository.pollRoundRecovery()).thenReturn("broken-payload");

        service.recoverStalledRounds();

        verify(repository).incrementRoundRecoveryMetric(RedisKeys.METRIC_GAME_ROUND_RECOVERY_FAILED);
        verify(repository, never()).requeueRoundRecovery(anyString());
        verify(nextRoundExecutor, never()).startNextRound(anyString(), anyInt());
    }

    private long pastTime() {
        return System.currentTimeMillis() - 1_000L;
    }
}
