package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.RoundPlaybackStartedDto;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class GameRoundStartService {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisScript<String> readyToPlayScript;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

    public GameRoundStartService(
            StringRedisTemplate redisTemplate,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("readyToPlayScript") RedisScript<String> readyToPlayScript) {
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.readyToPlayScript = readyToPlayScript;
    }

    public void scheduleForcePlaybackStart(String lobbyCode, int roundNo, int timeLimitSeconds) {
        log.info("게임 세션 라운드 시작 예약 - code: {}, roundNo: {}, timeout: 10s", lobbyCode, roundNo);
        scheduler.schedule(() -> forcePlaybackStart(lobbyCode, roundNo, timeLimitSeconds), 10, TimeUnit.SECONDS);
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

        if (Boolean.TRUE.equals(lockAcquired)) {
            log.warn("라운드 준비 타임아웃 발생. 강제 재생 시작 - code: {}, roundNo: {}", lobbyCode, roundNo);
            broadcastPlaybackStarted(lobbyCode, roundNo, timeLimitSeconds);
        } else {
            log.info("라운드 강제 재생 무시됨 (이미 시작됨) - code: {}, roundNo: {}", lobbyCode, roundNo);
        }
    }

    private void broadcastPlaybackStarted(String lobbyCode, int roundNo, int durationSeconds) {
        long serverStartedAt = System.currentTimeMillis();

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        redisTemplate.opsForHash().put(sessionKey, "playback_started_at", String.valueOf(serverStartedAt));

        RoundPlaybackStartedDto dto = RoundPlaybackStartedDto.builder()
                .type("ROUND_PLAYBACK_STARTED")
                .roundNo(roundNo)
                .serverStartedAt(serverStartedAt)
                .durationSeconds(durationSeconds)
                .build();

        messagingTemplate.convertAndSend(StompDestinations.subscribeGameRound(lobbyCode), dto);
    }
}
