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
    private final GameRoundProgressService self;

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
            @Lazy GameRoundProgressService self) {
        this.taskScheduler = taskScheduler;
        this.redisTemplate = redisTemplate;
        this.gameSessionJpaRepository = gameSessionJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.gameRealtimeNotifier = gameRealtimeNotifier;
        this.gameRoundEndService = gameRoundEndService;
        this.gameRoundStartService = gameRoundStartService;
        this.self = self;
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

        scheduledTasks.put(key, future);
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

        scheduledTasks.put(key, future);
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
                self.startNextRound(lobbyCode, nextRoundNo);
            } catch (Exception e) {
                log.error("다음 라운드 시작 예약 처리 중 예외 발생 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, e);
            }
        }, Instant.now().plusMillis(delayMillis));
    }

    /**
     * 다음 라운드 데이터를 로드하고 준비 신호(ROUND_READY)를 브로드캐스트합니다.
     */
    @Transactional
    public void startNextRound(String lobbyCode, int nextRoundNo) {
        String nextRoundLockKey = RedisKeys.gameSessionNextRoundLockKey(lobbyCode, nextRoundNo);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(nextRoundLockKey, "1", Duration.ofMinutes(5));

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.info("다음 라운드 시작 무시됨 (이미 시작 처리됨) - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo);
            return;
        }

        log.info("다음 라운드 시작 처리 - code: {}, roundNo: {}", lobbyCode, nextRoundNo);

        boolean registered = false;
        try {
            // 1. 게임 세션 조회
            GameSession gameSession = gameSessionJpaRepository.findActiveSessionByLobbyCode(lobbyCode)
                    .orElseThrow(() -> new NoSuchElementException("게임 세션을 찾을 수 없습니다. code: " + lobbyCode));

            // 2. DB 및 Redis 라운드 갱신
            gameSession.moveToRound(nextRoundNo);
            gameSessionJpaRepository.save(gameSession);

            String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_CURRENT_ROUND_NO, String.valueOf(nextRoundNo));
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_STATUS, "PLAYING");
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_ROUND_PHASE, "READY");

            // 3. 다음 라운드 MapItem 조회
            String roundsKey = RedisKeys.gameSessionRoundsKey(lobbyCode);
            String mapItemIdStr = redisTemplate.opsForList().index(roundsKey, nextRoundNo - 1);
            if (mapItemIdStr == null) {
                throw new NoSuchElementException("다음 라운드의 문제 ID를 Redis에서 찾을 수 없습니다. roundNo: " + nextRoundNo);
            }

            Long mapItemId = Long.parseLong(mapItemIdStr);
            MapItem mapItem = mapItemJpaRepository.findById(mapItemId)
                    .orElseThrow(() -> new NoSuchElementException("MapItem을 찾을 수 없습니다. id: " + mapItemId));

            // 4. 다음 라운드 DTO 구성
            long serverStartedAt = System.currentTimeMillis();
            int effectiveEndTime = mapItem.getStartTime() + gameSession.getLobby().getTimeLimitSeconds();

            RoundStartDto nextRoundDto = RoundStartDto.builder()
                    .type("ROUND_READY")
                    .videoId(mapItem.getVideoId())
                    .youtubeUrl(mapItem.getYoutubeUrl())
                    .startTime(mapItem.getStartTime())
                    .endTime(effectiveEndTime)
                    .timeLimitSeconds(gameSession.getLobby().getTimeLimitSeconds())
                    .roundNo(nextRoundNo)
                    .serverStartedAt(serverStartedAt)
                    .build();

            // 5. 트랜잭션 성공 후 이벤트 발행 및 재생 타이머 시동
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("다음 라운드 시작 트랜잭션 커밋 완료 - ROUND_READY 브로드캐스트. code: {}, roundNo: {}", lobbyCode, nextRoundNo);
                    gameRealtimeNotifier.notifyRoundStart(lobbyCode, nextRoundDto);

                    gameRoundStartService.scheduleForcePlaybackStart(lobbyCode, nextRoundNo, gameSession.getLobby().getTimeLimitSeconds());
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        log.warn("다음 라운드 시작 트랜잭션 롤백 감지 - 락 해제. code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo);
                        redisTemplate.delete(nextRoundLockKey);
                    }
                }
            });
            registered = true;
        } catch (Throwable t) {
            if (!registered) {
                log.warn("다음 라운드 시작 처리 중 예외 발생 - 락 해제. code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, t);
                redisTemplate.delete(nextRoundLockKey);
            }
            throw t;
        }
    }
}
