package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameRoundProgressService {

    private final TaskScheduler taskScheduler;
    private final StringRedisTemplate redisTemplate;
    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final GameRealtimeNotifier gameRealtimeNotifier;
    private final ApplicationContext applicationContext;

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

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
                GameRoundEndService endService = applicationContext.getBean(GameRoundEndService.class);
                endService.endRound(lobbyCode, roundNo);
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
                GameRoundEndService endService = applicationContext.getBean(GameRoundEndService.class);
                endService.endRound(lobbyCode, roundNo);
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
        log.info("다음 라운드 시작 예약 - code: {}, nextRoundNo: {}, delay: 10s", lobbyCode, nextRoundNo);
        
        taskScheduler.schedule(() -> {
            try {
                GameRoundProgressService self = applicationContext.getBean(GameRoundProgressService.class);
                self.startNextRound(lobbyCode, nextRoundNo);
            } catch (Exception e) {
                log.error("다음 라운드 시작 예약 처리 중 예외 발생 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, e);
            }
        }, Instant.now().plusSeconds(10));
    }

    /**
     * 다음 라운드 데이터를 로드하고 준비 신호(ROUND_READY)를 브로드캐스트합니다.
     */
    @Transactional
    public void startNextRound(String lobbyCode, int nextRoundNo) {
        log.info("다음 라운드 시작 처리 - code: {}, roundNo: {}", lobbyCode, nextRoundNo);

        // 1. 게임 세션 조회
        GameSession gameSession = gameSessionJpaRepository.findActiveSessionByLobbyCode(lobbyCode)
                .orElseThrow(() -> new NoSuchElementException("게임 세션을 찾을 수 없습니다. code: " + lobbyCode));

        // 2. DB 및 Redis 라운드 갱신
        gameSession.nextRound();
        gameSessionJpaRepository.save(gameSession);

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        redisTemplate.opsForHash().put(sessionKey, "current_round_no", String.valueOf(nextRoundNo));
        redisTemplate.opsForHash().put(sessionKey, "status", "READY");

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

                GameRoundStartService startService = applicationContext.getBean(GameRoundStartService.class);
                startService.scheduleForcePlaybackStart(lobbyCode, nextRoundNo, gameSession.getLobby().getTimeLimitSeconds());
            }
        });
    }
}
