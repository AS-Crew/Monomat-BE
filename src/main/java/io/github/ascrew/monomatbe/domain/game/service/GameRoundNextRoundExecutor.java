package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundStartDto;
import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

@Slf4j
@Component
public class GameRoundNextRoundExecutor {

    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final GameRealtimeNotifier gameRealtimeNotifier;
    private final StringRedisTemplate redisTemplate;
    private final GameRoundStartService gameRoundStartService;

    @Lazy
    public GameRoundNextRoundExecutor(
            GameSessionJpaRepository gameSessionJpaRepository,
            MapItemJpaRepository mapItemJpaRepository,
            GameRealtimeNotifier gameRealtimeNotifier,
            StringRedisTemplate redisTemplate,
            @Lazy GameRoundStartService gameRoundStartService) {
        this.gameSessionJpaRepository = gameSessionJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.gameRealtimeNotifier = gameRealtimeNotifier;
        this.redisTemplate = redisTemplate;
        this.gameRoundStartService = gameRoundStartService;
    }

    /**
     * 다음 라운드 데이터를 로드하고 준비 신호(ROUND_READY)를 브로드캐스트합니다.
     */
    @Transactional
    public void startNextRound(String lobbyCode, int nextRoundNo) {
        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);

        // [중요: 락과 완료 상태 분리]
        // 1. 이미 완료되었는지 Redis 필드로 1차 검증
        String currentRoundStr = (String) redisTemplate.opsForHash().get(sessionKey, RedisKeys.FIELD_CURRENT_ROUND_NO);
        if (currentRoundStr != null && Integer.parseInt(currentRoundStr) >= nextRoundNo) {
            log.info("다음 라운드가 이미 시작되었습니다. (Redis 검증 통과) - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo);
            return;
        }

        // 2. 동시성 제어를 위한 처리 중 락 획득 (짧은 TTL 10초)
        String nextRoundLockKey = RedisKeys.gameSessionNextRoundLockKey(lobbyCode, nextRoundNo);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(nextRoundLockKey, "1", Duration.ofSeconds(10));

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.info("다음 라운드 시작 처리 중이므로 무시됨 - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo);
            return;
        }

        log.info("다음 라운드 시작 처리 실행 - code: {}, roundNo: {}", lobbyCode, nextRoundNo);

        boolean registered = false;
        try {
            // 3. 게임 세션 조회
            GameSession gameSession = gameSessionJpaRepository.findActiveSessionByLobbyCode(lobbyCode)
                    .orElseThrow(() -> new NoSuchElementException("게임 세션을 찾을 수 없습니다. code: " + lobbyCode));

            // DB에서 2차 완료 여부 검증
            if (gameSession.getCurrentRoundNo() >= nextRoundNo) {
                log.info("다음 라운드가 이미 시작되었습니다. (DB 검증 통과) - code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo);
                return;
            }

            // 4. DB 및 Redis 라운드 갱신
            gameSession.moveToNextRound(nextRoundNo);
            gameSessionJpaRepository.save(gameSession);

            redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_CURRENT_ROUND_NO, String.valueOf(nextRoundNo));
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_STATUS, "PLAYING");
            redisTemplate.opsForHash().put(sessionKey, RedisKeys.FIELD_ROUND_PHASE, "READY");

            // 5. 다음 라운드 MapItem 조회
            String roundsKey = RedisKeys.gameSessionRoundsKey(lobbyCode);
            String mapItemIdStr = redisTemplate.opsForList().index(roundsKey, nextRoundNo - 1);
            if (mapItemIdStr == null) {
                throw new NoSuchElementException("다음 라운드의 문제 ID를 Redis에서 찾을 수 없습니다. roundNo: " + nextRoundNo);
            }

            Long mapItemId = Long.parseLong(mapItemIdStr);
            MapItem mapItem = mapItemJpaRepository.findById(mapItemId)
                    .orElseThrow(() -> new NoSuchElementException("MapItem을 찾을 수 없습니다. id: " + mapItemId));

            // 6. 다음 라운드 DTO 구성
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

            // 7. 트랜잭션 성공 후 이벤트 발행 및 재생 타이머 시동
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("다음 라운드 시작 트랜잭션 커밋 완료 - ROUND_READY 브로드캐스트. code: {}, roundNo: {}", lobbyCode, nextRoundNo);
                    gameRealtimeNotifier.notifyRoundStart(lobbyCode, nextRoundDto);

                    gameRoundStartService.scheduleForcePlaybackStart(lobbyCode, nextRoundNo, gameSession.getLobby().getTimeLimitSeconds());
                }

                @Override
                public void afterCompletion(int status) {
                    // 완료 또는 실패에 관계없이 처리 중 락을 즉시 해제하여 락 방치를 차단한다.
                    log.info("다음 라운드 시작 트랜잭션 완료(status={}) - 처리 락 해제. code: {}, nextRoundNo: {}", status, lobbyCode, nextRoundNo);
                    redisTemplate.delete(nextRoundLockKey);
                }
            });
            registered = true;
        } finally {
            if (!registered) {
                // 트랜잭션 동기화 등록조차 실패하고 예외 발생 시 예외 안전성 확보를 위한 즉시 해제
                log.warn("트랜잭션 동기화 등록 실패 - 처리 락 해제. code: {}, nextRoundNo: {}", lobbyCode, nextRoundNo);
                redisTemplate.delete(nextRoundLockKey);
            }
        }
    }
}
