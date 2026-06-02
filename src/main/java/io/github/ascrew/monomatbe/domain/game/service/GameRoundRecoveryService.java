package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 서버 재시작 시 활성 게임 세션 복구 서비스.
 *
 * 인메모리 TaskScheduler 예약은 인스턴스 재시작 시 유실되므로, 부팅 직후(ApplicationReadyEvent)
 * DB의 active 세션을 스캔해 Redis에 기록된 round_phase(READY/PLAYING/ENDED)에 따라 라운드 종료/
 * 강제 재생 시작/다음 라운드 시작 타이머를 재구성한다.
 *
 * 상시 동작하며 정지된 다음 라운드를 큐 기반으로 재트리거하는 복구는
 * {@link GameRoundStallRecoveryService}가 담당한다. (보완적 관계)
 */
@Slf4j
@Service
public class GameRoundRecoveryService {

    private final GameSessionJpaRepository gameSessionJpaRepository;
    private final StringRedisTemplate redisTemplate;
    private final GameRoundEndService gameRoundEndService;
    private final GameRoundProgressService gameRoundProgressService;
    private final GameRoundStartService gameRoundStartService;
    private final GameRoundNextRoundExecutor gameRoundNextRoundExecutor;

    @Lazy
    public GameRoundRecoveryService(
            GameSessionJpaRepository gameSessionJpaRepository,
            StringRedisTemplate redisTemplate,
            @Lazy GameRoundEndService gameRoundEndService,
            @Lazy GameRoundProgressService gameRoundProgressService,
            @Lazy GameRoundStartService gameRoundStartService,
            @Lazy GameRoundNextRoundExecutor gameRoundNextRoundExecutor) {
        this.gameSessionJpaRepository = gameSessionJpaRepository;
        this.redisTemplate = redisTemplate;
        this.gameRoundEndService = gameRoundEndService;
        this.gameRoundProgressService = gameRoundProgressService;
        this.gameRoundStartService = gameRoundStartService;
        this.gameRoundNextRoundExecutor = gameRoundNextRoundExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverActiveSessions() {
        log.info("GameRoundRecoveryService: 서버 시작에 따른 활성 게임 세션 복구 프로세스 시작");

        List<GameSession> activeSessions = gameSessionJpaRepository.findAllActiveSessionsWithLobby();
        log.info("복구 대상 active DB 세션 개수: {}", activeSessions.size());

        for (GameSession session : activeSessions) {
            String lobbyCode = session.getLobby().getInviteCode();
            try {
                recoverSession(lobbyCode, session);
            } catch (Exception e) {
                log.error("게임 세션 복구 실패 - code: {}", lobbyCode, e);
            }
        }
    }

    private void recoverSession(String lobbyCode, GameSession session) {
        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        List<Object> hashValues = redisTemplate.opsForHash().multiGet(sessionKey, List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ));

        if (hashValues.get(0) == null) {
            log.info("Redis에 세션 정보 없음, 복구 건너뜀 (이미 만료됨) - code: {}", lobbyCode);
            return;
        }

        int roundNo = Integer.parseInt((String) hashValues.get(0));
        int timeLimitSeconds = hashValues.get(1) != null ? Integer.parseInt((String) hashValues.get(1)) : 30;
        String status = (String) hashValues.get(2);
        String roundPhase = hashValues.get(3) != null ? (String) hashValues.get(3) : "READY";

        log.info("게임 세션 복구 중 - code: {}, roundNo: {}, status: {}, phase: {}", lobbyCode, roundNo, status, roundPhase);

        if ("FINISHED".equals(status) || "FINISHED".equals(roundPhase)) {
            log.info("이미 종료된 세션 - code: {}", lobbyCode);
            return;
        }

        if ("PLAYING".equals(roundPhase)) {
            // PLAYING 단계 복구
            String playbackStartedKey = RedisKeys.gameSessionRoundPlaybackStartedAtField(roundNo);
            String playbackStartedAtStr = (String) redisTemplate.opsForHash().get(sessionKey, playbackStartedKey);
            if (playbackStartedAtStr != null) {
                long serverStartedAt = Long.parseLong(playbackStartedAtStr);
                long elapsed = System.currentTimeMillis() - serverStartedAt;
                long limitTimeMillis = timeLimitSeconds * 1000L + 1500L;

                if (elapsed >= limitTimeMillis) {
                    log.warn("시간 초과가 이미 발생함 -> 즉시 종료 처리. code: {}, roundNo: {}", lobbyCode, roundNo);
                    gameRoundEndService.endRound(lobbyCode, roundNo);
                } else {
                    long remainingDelay = limitTimeMillis - elapsed;
                    log.info("남은 시간으로 라운드 종료 타이머 복구 등록 - code: {}, roundNo: {}, remaining: {}ms", lobbyCode, roundNo, remainingDelay);
                    gameRoundProgressService.rescheduleRoundEnd(lobbyCode, roundNo, remainingDelay);
                }
            } else {
                log.warn("PLAYING 단계이나 재생 시작 시각이 없음 -> 강제 재생 시작 처리. code: {}, roundNo: {}", lobbyCode, roundNo);
                gameRoundStartService.scheduleForcePlaybackStart(lobbyCode, roundNo, timeLimitSeconds);
            }
        } else if ("READY".equals(roundPhase)) {
            // READY 단계 복구: 재생 강제 시작 타이머 재등록 (10초 대기 시간 중 일부가 흘렀겠지만, 안전하게 새로 10초 대기)
            log.info("READY 단계 복구 -> 강제 재생 시작 타이머 복구 등록 - code: {}, roundNo: {}", lobbyCode, roundNo);
            gameRoundStartService.scheduleForcePlaybackStart(lobbyCode, roundNo, timeLimitSeconds);
        } else if ("ENDED".equals(roundPhase)) {
            // ENDED 단계 복구: 다음 라운드 시작 타이머 복구
            String endedAtField = RedisKeys.gameSessionRoundEndedAtField(roundNo);
            String endedAtStr = (String) redisTemplate.opsForHash().get(sessionKey, endedAtField);
            if (endedAtStr != null) {
                long endedAt = Long.parseLong(endedAtStr);
                long elapsed = System.currentTimeMillis() - endedAt;
                long limitTimeMillis = 10000L; // 10초 결과 화면 노출

                if (elapsed >= limitTimeMillis) {
                    log.info("결과화면 대기 시간 초과 -> 즉시 다음 라운드 시작. code: {}, nextRound: {}", lobbyCode, roundNo + 1);
                    gameRoundNextRoundExecutor.startNextRound(lobbyCode, roundNo + 1);
                } else {
                    long remainingDelay = limitTimeMillis - elapsed;
                    log.info("남은 시간으로 다음 라운드 시작 타이머 복구 등록 - code: {}, nextRound: {}, remaining: {}ms", lobbyCode, roundNo + 1, remainingDelay);
                    gameRoundProgressService.scheduleNextRoundWithDelay(lobbyCode, roundNo + 1, remainingDelay);
                }
            } else {
                log.warn("ENDED 단계이나 라운드 종료 시각이 없음 -> 즉시 다음 라운드 시작. code: {}, nextRound: {}", lobbyCode, roundNo + 1);
                gameRoundNextRoundExecutor.startNextRound(lobbyCode, roundNo + 1);
            }
        }
    }
}
