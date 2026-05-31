package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundPlaybackStartedDto;
import io.github.ascrew.monomatbe.global.constant.GameEventTypes;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class GameRoundStartService {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisScript<String> readyToPlayScript;
    private final TaskScheduler taskScheduler;
    private final ApplicationContext applicationContext;

    public GameRoundStartService(
            StringRedisTemplate redisTemplate,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("readyToPlayScript") RedisScript<String> readyToPlayScript,
            TaskScheduler taskScheduler,
            ApplicationContext applicationContext) {
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.readyToPlayScript = readyToPlayScript;
        this.taskScheduler = taskScheduler;
        this.applicationContext = applicationContext;
    }

    public void scheduleForcePlaybackStart(String lobbyCode, int roundNo, int timeLimitSeconds) {
        log.info("게임 세션 라운드 시작 예약 - code: {}, roundNo: {}, timeout: 10s", lobbyCode, roundNo);
        taskScheduler.schedule(() -> forcePlaybackStart(lobbyCode, roundNo, timeLimitSeconds), Instant.now().plusSeconds(10));
    }

    public void processReadyToPlay(String lobbyCode, String userIdentifier, int roundNo) {
        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        String readyKey = RedisKeys.gameSessionRoundReadyKey(lobbyCode, roundNo);
        String participantsKey = RedisKeys.lobbyParticipantsKey(lobbyCode);
        String playbackLockKey = RedisKeys.gameSessionPlaybackLockKey(lobbyCode, roundNo);

        String result = redisTemplate.execute(
                readyToPlayScript,
                List.of(sessionKey, readyKey, participantsKey, playbackLockKey),
                userIdentifier,
                String.valueOf(roundNo),
                "7200" // 2시간 TTL
        );

        log.info("라운드 재생 준비 처리 - code: {}, user: {}, roundNo: {}, result: {}", lobbyCode, userIdentifier, roundNo, result);

        if ("ALL_READY".equals(result)) {
            String timeLimitStr = (String) redisTemplate.opsForHash().get(sessionKey, "time_limit_seconds");
            int timeLimitSeconds = timeLimitStr != null ? Integer.parseInt(timeLimitStr) : 30;
            broadcastPlaybackStarted(lobbyCode, roundNo, timeLimitSeconds);
        }
    }

    private void forcePlaybackStart(String lobbyCode, int roundNo, int timeLimitSeconds) {
        String playbackLockKey = RedisKeys.gameSessionPlaybackLockKey(lobbyCode, roundNo);
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(playbackLockKey, "1", Duration.ofHours(2));

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        String playbackStartedKey = RedisKeys.gameSessionRoundPlaybackStartedAtField(roundNo);
        String playbackStartedAtVal = (String) redisTemplate.opsForHash().get(sessionKey, playbackStartedKey);

        if (Boolean.TRUE.equals(lockAcquired)) {
            log.warn("라운드 준비 타임아웃 발생. 강제 재생 시작 - code: {}, roundNo: {}", lobbyCode, roundNo);
            broadcastPlaybackStarted(lobbyCode, roundNo, timeLimitSeconds);
        } else if (playbackStartedAtVal == null) {
            log.warn("라운드 락은 획득되었으나 재생 시작 시간이 기록되지 않은 비정상 상태 감지. 강제 재생 시작으로 복구 - code: {}, roundNo: {}", lobbyCode, roundNo);
            broadcastPlaybackStarted(lobbyCode, roundNo, timeLimitSeconds);
        } else {
            log.info("라운드 강제 재생 무시됨 (이미 시작됨) - code: {}, roundNo: {}", lobbyCode, roundNo);
        }
    }

    private void broadcastPlaybackStarted(String lobbyCode, int roundNo, int durationSeconds) {
        long serverStartedAt = System.currentTimeMillis();

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        String playbackStartedKey = RedisKeys.gameSessionRoundPlaybackStartedAtField(roundNo);
        Boolean isSaved = redisTemplate.opsForHash().putIfAbsent(sessionKey, playbackStartedKey, String.valueOf(serverStartedAt));

        if (Boolean.TRUE.equals(isSaved)) {
            redisTemplate.opsForHash().put(sessionKey, "status", "PLAYING");

            RoundPlaybackStartedDto dto = RoundPlaybackStartedDto.builder()
                    .type(GameEventTypes.ROUND_PLAYBACK_STARTED)
                    .roundNo(roundNo)
                    .serverStartedAt(serverStartedAt)
                    .durationSeconds(durationSeconds)
                    .build();

            messagingTemplate.convertAndSend(StompDestinations.subscribeGameRound(lobbyCode), dto);

            // 라운드 종료 스케줄링 호출
            try {
                GameRoundProgressService progressService = applicationContext.getBean(GameRoundProgressService.class);
                progressService.scheduleRoundEnd(lobbyCode, roundNo, durationSeconds);
            } catch (Exception e) {
                log.error("라운드 종료 자동 스케줄링 예약 실패 - code: {}, roundNo: {}", lobbyCode, roundNo, e);
            }
        } else {
            log.info("이미 다른 스레드/서버에서 재생 시작 시각이 기록되어 브로드캐스트를 스킵합니다 - code: {}, roundNo: {}", lobbyCode, roundNo);
        }
    }
}

