package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.redis.GameRoundRecoveryRepository;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class GameRoundProgressService {

    /** 라운드 결과 화면 노출 후 다음 라운드 시작까지의 지연(ms). */
    private static final long NEXT_ROUND_DELAY_MS = 10_000L;

    /** 복구 워커가 다음 라운드 진행 여부를 점검하기까지의 추가 여유(ms). */
    private static final long RECOVERY_GRACE_MS = 10_000L;

    private final TaskScheduler taskScheduler;
    private final StringRedisTemplate redisTemplate;
    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final GameRealtimeNotifier gameRealtimeNotifier;
    private final GameRoundEndService gameRoundEndService;
    private final GameRoundStartService gameRoundStartService;
    private final GameRoundNextRoundExecutor gameRoundNextRoundExecutor;
    private final GameRoundRecoveryRepository gameRoundRecoveryRepository;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    @Lazy
    public GameRoundProgressService(
            TaskScheduler taskScheduler,
            StringRedisTemplate redisTemplate,
            GameSessionJpaRepository gameSessionJpaRepository,
            MapItemJpaRepository mapItemJpaRepository,
            GameRealtimeNotifier gameRealtimeNotifier,
            GameRoundEndService gameRoundEndService,
            GameRoundStartService gameRoundStartService,
            @Lazy GameRoundNextRoundExecutor gameRoundNextRoundExecutor,
            GameRoundRecoveryRepository gameRoundRecoveryRepository) {
        this.taskScheduler = taskScheduler;
        this.redisTemplate = redisTemplate;
        this.gameSessionJpaRepository = gameSessionJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.gameRealtimeNotifier = gameRealtimeNotifier;
        this.gameRoundEndService = gameRoundEndService;
        this.gameRoundStartService = gameRoundStartService;
        this.gameRoundNextRoundExecutor = gameRoundNextRoundExecutor;
        this.gameRoundRecoveryRepository = gameRoundRecoveryRepository;
    }

    /**
     * 라운드 종료 스케줄링을 등록합니다. (재생 시작 시점 기준 1.5초 완충 시간 포함)
     */
    public void scheduleRoundEnd(String lobbyCode, int roundNo, int timeLimitSeconds) {
        long delayMillis = (timeLimitSeconds * 1000L) + 1500L;
        log.info("라운드 종료 자동 태스크 예약 - code: {}, roundNo: {}, delay: {}ms", lobbyCode, roundNo, delayMillis);

        String key = lobbyCode + ":" + roundNo;
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                scheduledTasks.remove(key);
                gameRoundEndService.endRound(lobbyCode, roundNo);
            } catch (Exception e) {
                log.error("자동 라운드 종료 처리 중 예외 발생 - code: {}, roundNo: {}", lobbyCode, roundNo, e);
            }
        }, Instant.now().plusMillis(delayMillis));

        ScheduledFuture<?> oldFuture = scheduledTasks.put(key, future);
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
    }

    /**
     * 특정 라운드의 종료 타이머가 예약되어 있는지 확인합니다.
     */
    public boolean isRoundEndScheduled(String lobbyCode, int roundNo) {
        String key = lobbyCode + ":" + roundNo;
        ScheduledFuture<?> future = scheduledTasks.get(key);
        return future != null && !future.isDone() && !future.isCancelled();
    }

    /**
     * 유실된 라운드 종료 타이머를 특정 지연 시간으로 다시 등록합니다.
     */
    public void rescheduleRoundEnd(String lobbyCode, int roundNo, long remainingDelayMillis) {
        log.info("유실된 라운드 종료 자동 태스크 복구 및 재등록 - code: {}, roundNo: {}, delay: {}ms", lobbyCode, roundNo, remainingDelayMillis);
        String key = lobbyCode + ":" + roundNo;
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                scheduledTasks.remove(key);
                gameRoundEndService.endRound(lobbyCode, roundNo);
            } catch (Exception e) {
                log.error("자동 라운드 종료 처리 중 예외 발생 - code: {}, roundNo: {}", lobbyCode, roundNo, e);
            }
        }, Instant.now().plusMillis(remainingDelayMillis));

        ScheduledFuture<?> oldFuture = scheduledTasks.put(key, future);
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
    }

    /**
     * 다음 라운드 시작 스케줄링을 등록합니다. (결과 화면 노출 10초)
     */
    public void scheduleNextRound(String lobbyCode, int nextRoundNo) {
        scheduleNextRoundWithDelay(lobbyCode, nextRoundNo, NEXT_ROUND_DELAY_MS);
    }

    /**
     * 특정 지연 시간(ms) 후 다음 라운드 시작 스케줄링을 등록합니다.
     *
     * 인메모리 TaskScheduler 예약은 인스턴스 재시작 시 유실되므로, durable 복구 마커와
     * 복구 큐 적재를 함께 수행해 정지된 라운드를 복구 워커(GameRoundStallRecoveryService)가
     * 재트리거할 수 있게 한다. 실제 진행은 GameRoundNextRoundExecutor.startNextRound의
     * 멱등 락으로 보호되므로 인메모리 예약과 복구가 동시에 동작해도 중복 진행되지 않는다.
     */
    public void scheduleNextRoundWithDelay(String lobbyCode, int nextRoundNo, long delayMillis) {
        log.info("다음 라운드 시작 예약 - code: {}, nextRoundNo: {}, delay: {}ms", lobbyCode, nextRoundNo, delayMillis);

        long nextRoundStartAt = System.currentTimeMillis() + delayMillis;

        // durable 상태 마커: 다음 라운드 시작 예정 시각을 세션 해시에 기록한다. (복구 판별/진단용)
        markNextRoundStartAtQuietly(lobbyCode, nextRoundStartAt);

        // 인메모리 예약이 유실되어도 진행되도록 durable 복구 요청을 적재한다.
        // 예약 지연 + grace 이후부터 복구 워커가 점검하도록 nextRetryAt을 설정한다.
        try {
            gameRoundRecoveryRepository.enqueueRoundRecovery(
                    lobbyCode, nextRoundNo, nextRoundStartAt + RECOVERY_GRACE_MS);
        } catch (Exception e) {
            log.warn("다음 라운드 복구 큐 적재 실패 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, e);
        }

        taskScheduler.schedule(() -> {
            try {
                gameRoundNextRoundExecutor.startNextRound(lobbyCode, nextRoundNo);
            } catch (Exception e) {
                log.error("다음 라운드 시작 예약 처리 중 예외 발생 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, e);
            }
        }, Instant.now().plusMillis(delayMillis));
    }

    /** 다음 라운드 시작 예정 시각 마커를 세션 해시에 best-effort로 기록한다. */
    private void markNextRoundStartAtQuietly(String lobbyCode, long nextRoundStartAt) {
        try {
            redisTemplate.opsForHash().put(
                    RedisKeys.gameSessionKey(lobbyCode),
                    RedisKeys.FIELD_NEXT_ROUND_START_AT,
                    String.valueOf(nextRoundStartAt));
        } catch (Exception e) {
            log.warn("다음 라운드 시작 마커 기록 실패 - code: {}", lobbyCode, e);
        }
    }
}
