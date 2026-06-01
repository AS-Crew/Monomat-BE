package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.CurrentRoundStatusResponse;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameSessionQueryService {

    private final StringRedisTemplate redisTemplate;
    private final LobbyRepository lobbyRepository;
    private final ApplicationContext applicationContext;

    public CurrentRoundStatusResponse getCurrentRoundStatus(String lobbyCode, String userIdentifier) {
        if (!lobbyRepository.isParticipant(lobbyCode, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "로비 참가자만 조회할 수 있습니다.");
        }

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        
        List<Object> hashValues = redisTemplate.opsForHash().multiGet(sessionKey, List.of("current_round_no", "time_limit_seconds", "status"));
        
        if (hashValues.get(0) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 게임 세션이 없습니다.");
        }

        int roundNo = Integer.parseInt((String) hashValues.get(0));
        int timeLimitSeconds = hashValues.get(1) != null ? Integer.parseInt((String) hashValues.get(1)) : 30;
        String redisStatus = hashValues.get(2) != null ? (String) hashValues.get(2) : "READY";
        
        String playbackStartedKey = RedisKeys.gameSessionRoundPlaybackStartedAtField(roundNo);
        String playbackStartedAtStr = (String) redisTemplate.opsForHash().get(sessionKey, playbackStartedKey);
        Long serverStartedAt = playbackStartedAtStr != null ? Long.parseLong(playbackStartedAtStr) : null;

        String playbackLockKey = RedisKeys.gameSessionPlaybackLockKey(lobbyCode, roundNo);
        boolean isPlaying = Boolean.TRUE.equals(redisTemplate.hasKey(playbackLockKey));

        // 타이머 유실 대응 복구 메커니즘 (Self-Healing)
        if ("PLAYING".equals(redisStatus) && serverStartedAt != null) {
            long elapsed = System.currentTimeMillis() - serverStartedAt;
            long limitTimeMillis = timeLimitSeconds * 1000L + 1500L;
            
            if (elapsed >= limitTimeMillis) {
                // 시간 초과가 이미 발생했으나 라운드가 미종료 상태인 경우 -> 즉시 조기 종료 처리
                log.warn("getCurrentRoundStatus: 타이머 유실 감지 - 시간 초과로 라운드를 즉시 종료합니다. code: {}, roundNo: {}", lobbyCode, roundNo);
                try {
                    GameRoundEndService endService = applicationContext.getBean(GameRoundEndService.class);
                    endService.endRound(lobbyCode, roundNo);
                } catch (Exception e) {
                    log.error("getCurrentRoundStatus: 타이머 유실 복구 중 라운드 종료 실패", e);
                }
                isPlaying = false;
                redisStatus = "ENDED";
            } else {
                // 아직 제한 시간 이전이나 스케줄러에 등록되어 있지 않은 경우 -> 타이머 재등록
                try {
                    GameRoundProgressService progressService = applicationContext.getBean(GameRoundProgressService.class);
                    if (!progressService.isRoundEndScheduled(lobbyCode, roundNo)) {
                        long remainingDelay = limitTimeMillis - elapsed;
                        progressService.rescheduleRoundEnd(lobbyCode, roundNo, remainingDelay);
                    }
                } catch (Exception e) {
                    log.error("getCurrentRoundStatus: 타이머 유실 복구 중 스케줄링 실패", e);
                }
            }
        }

        // status는 코드와 이전 이슈 기준을 맞추기 위해 isPlaying 상태에 따라 PLAYING/WAITING으로 매핑하여 반환하되,
        // FINISHED이거나 ENDED인 경우를 구분합니다.
        String responseStatus;
        if ("FINISHED".equals(redisStatus)) {
            responseStatus = "FINISHED";
        } else if ("ENDED".equals(redisStatus)) {
            responseStatus = "WAITING"; // 라운드 종료 상태(결과화면 대기)
        } else {
            responseStatus = isPlaying ? "PLAYING" : "WAITING";
        }

        return CurrentRoundStatusResponse.builder()
                .roundNo(roundNo)
                .status(responseStatus)
                .timeLimitSeconds(timeLimitSeconds)
                .serverStartedAt(serverStartedAt)
                .build();
    }
}
