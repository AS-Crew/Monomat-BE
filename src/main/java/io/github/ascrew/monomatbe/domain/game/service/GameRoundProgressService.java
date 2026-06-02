package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class GameRoundProgressService {

    private final TaskScheduler taskScheduler;
    private final StringRedisTemplate redisTemplate;
    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final GameRealtimeNotifier gameRealtimeNotifier;
    private final GameRoundEndService gameRoundEndService;
    private final GameRoundStartService gameRoundStartService;
    private final GameRoundNextRoundExecutor gameRoundNextRoundExecutor;

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
            @Lazy GameRoundNextRoundExecutor gameRoundNextRoundExecutor) {
        this.taskScheduler = taskScheduler;
        this.redisTemplate = redisTemplate;
        this.gameSessionJpaRepository = gameSessionJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.gameRealtimeNotifier = gameRealtimeNotifier;
        this.gameRoundEndService = gameRoundEndService;
        this.gameRoundStartService = gameRoundStartService;
        this.gameRoundNextRoundExecutor = gameRoundNextRoundExecutor;
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
        scheduleNextRoundWithDelay(lobbyCode, nextRoundNo, 10000L);
    }

    /**
     * 특정 지연 시간(ms) 후 다음 라운드 시작 스케줄링을 등록합니다.
     */
    public void scheduleNextRoundWithDelay(String lobbyCode, int nextRoundNo, long delayMillis) {
        log.info("다음 라운드 시작 예약 - code: {}, nextRoundNo: {}, delay: {}ms", lobbyCode, nextRoundNo, delayMillis);
        
        taskScheduler.schedule(() -> {
            try {
                gameRoundNextRoundExecutor.startNextRound(lobbyCode, nextRoundNo);
            } catch (Exception e) {
                log.error("다음 라운드 시작 예약 처리 중 예외 발생 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, e);
            }
        }, Instant.now().plusMillis(delayMillis));
    }
}
