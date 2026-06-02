package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.redis.GameRoundRecoveryRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameRoundProgressService {

    /** 라운드 결과 화면 노출 후 다음 라운드 시작까지의 지연(초). */
    private static final int NEXT_ROUND_DELAY_SECONDS = 10;

    /** 복구 워커가 다음 라운드 진행 여부를 점검하기까지의 추가 여유(초). */
    private static final int RECOVERY_GRACE_SECONDS = 10;

    /** 다음 라운드 시작 멱등 락 TTL. */
    private static final Duration NEXT_ROUND_LOCK_TTL = Duration.ofMinutes(5);

    private final TaskScheduler taskScheduler;
    private final StringRedisTemplate redisTemplate;
    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final GameRealtimeNotifier gameRealtimeNotifier;
    private final ApplicationContext applicationContext;
    private final GameRoundRecoveryRepository gameRoundRecoveryRepository;

    /**
     * 라운드 종료 스케줄링을 등록합니다. (재생 시작 시점 기준 1.5초 완충 시간 포함)
     */
    public void scheduleRoundEnd(String lobbyCode, int roundNo, int timeLimitSeconds) {
        long delayMillis = (timeLimitSeconds * 1000L) + 1500L;
        log.info("라운드 종료 자동 태스크 예약 - code: {}, roundNo: {}, delay: {}ms", lobbyCode, roundNo, delayMillis);
        
        taskScheduler.schedule(() -> {
            try {
                GameRoundEndService endService = applicationContext.getBean(GameRoundEndService.class);
                endService.endRound(lobbyCode, roundNo);
            } catch (Exception e) {
                log.error("자동 라운드 종료 처리 중 예외 발생 - code: {}, roundNo: {}", lobbyCode, roundNo, e);
            }
        }, Instant.now().plusMillis(delayMillis));
    }

    /**
     * 다음 라운드 시작 스케줄링을 등록합니다. (결과 화면 노출 10초)
     *
     * 인메모리 TaskScheduler 예약은 인스턴스 재시작 시 유실되므로, durable 복구 마커와
     * 복구 큐 적재를 함께 수행해 정지된 라운드를 복구 워커가 재트리거할 수 있게 한다.
     */
    public void scheduleNextRound(String lobbyCode, int nextRoundNo) {
        log.info("다음 라운드 시작 예약 - code: {}, nextRoundNo: {}, delay: {}s", lobbyCode, nextRoundNo, NEXT_ROUND_DELAY_SECONDS);

        long nextRoundStartAt = System.currentTimeMillis() + NEXT_ROUND_DELAY_SECONDS * 1000L;

        // durable 상태 마커: 다음 라운드 시작 예정 시각을 세션 해시에 기록한다. (복구 판별/진단용)
        markNextRoundStartAtQuietly(lobbyCode, nextRoundStartAt);

        // 인메모리 예약이 유실되어도 진행되도록 durable 복구 요청을 적재한다.
        // 예약 지연 + grace 이후부터 복구 워커가 점검하도록 nextRetryAt을 설정한다.
        gameRoundRecoveryRepository.enqueueRoundRecovery(
                lobbyCode, nextRoundNo, nextRoundStartAt + RECOVERY_GRACE_SECONDS * 1000L);

        taskScheduler.schedule(() -> {
            try {
                GameRoundProgressService self = applicationContext.getBean(GameRoundProgressService.class);
                self.startNextRound(lobbyCode, nextRoundNo);
            } catch (Exception e) {
                log.error("다음 라운드 시작 예약 처리 중 예외 발생 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo, e);
            }
        }, Instant.now().plusSeconds(NEXT_ROUND_DELAY_SECONDS));
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

    /**
     * 다음 라운드 데이터를 로드하고 준비 신호(ROUND_READY)를 브로드캐스트합니다.
     */
    @Transactional
    public void startNextRound(String lobbyCode, int nextRoundNo) {
        log.info("다음 라운드 시작 처리 - code: {}, roundNo: {}", lobbyCode, nextRoundNo);

        /*
         * 멱등 락: 인메모리 예약과 복구 워커가 동시에 호출해도 한 번만 진행시킨다.
         * GameSession.nextRound()는 currentRoundNo를 증가시키는 비멱등 연산이므로,
         * 락 없이 중복 실행되면 라운드를 건너뛰게 된다.
         */
        String nextLockKey = RedisKeys.gameSessionRoundNextLockKey(lobbyCode, nextRoundNo);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(nextLockKey, "1", NEXT_ROUND_LOCK_TTL);
        if (Boolean.FALSE.equals(lockAcquired)) {
            log.info("다음 라운드 시작 무시됨 (이미 진행/처리됨) - code: {}, roundNo: {}", lobbyCode, nextRoundNo);
            return;
        }

        // 1. 게임 세션 조회
        GameSession gameSession = gameSessionJpaRepository.findActiveSessionByLobbyCode(lobbyCode)
                .orElseThrow(() -> new NoSuchElementException("게임 세션을 찾을 수 없습니다. code: " + lobbyCode));

        // 2. DB 및 Redis 라운드 갱신 (DB는 Dirty Checking으로 커밋 시 반영)
        gameSession.nextRound();

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_CURRENT_ROUND_NO, String.valueOf(nextRoundNo));
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

        // 5. 트랜잭션 성공 후 이벤트 발행 및 재생 타이머 시동, 롤백 시 멱등 락 해제
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("다음 라운드 시작 트랜잭션 커밋 완료 - ROUND_READY 브로드캐스트. code: {}, roundNo: {}", lobbyCode, nextRoundNo);
                gameRealtimeNotifier.notifyRoundStart(lobbyCode, nextRoundDto);

                GameRoundStartService startService = applicationContext.getBean(GameRoundStartService.class);
                startService.scheduleForcePlaybackStart(lobbyCode, nextRoundNo, gameSession.getLobby().getTimeLimitSeconds());
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    // 롤백 시 멱등 락을 해제해야 복구 워커가 다음 라운드를 다시 진행시킬 수 있다.
                    log.warn("다음 라운드 시작 트랜잭션 롤백 감지 - 멱등 락 해제. code: {}, roundNo: {}", lobbyCode, nextRoundNo);
                    redisTemplate.delete(nextLockKey);
                }
            }
        });
    }
}
