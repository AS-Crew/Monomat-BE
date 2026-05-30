package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.CurrentRoundStatusResponse;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GameSessionQueryService {

    private final StringRedisTemplate redisTemplate;
    private final LobbyRepository lobbyRepository;

    public CurrentRoundStatusResponse getCurrentRoundStatus(String lobbyCode, String userIdentifier) {
        if (!lobbyRepository.isParticipant(lobbyCode, userIdentifier)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "로비 참가자만 조회할 수 있습니다.");
        }

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        
        List<Object> hashValues = redisTemplate.opsForHash().multiGet(sessionKey, List.of("current_round_no", "time_limit_seconds"));
        
        if (hashValues.get(0) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 게임 세션이 없습니다.");
        }

        int roundNo = Integer.parseInt((String) hashValues.get(0));
        int timeLimitSeconds = hashValues.get(1) != null ? Integer.parseInt((String) hashValues.get(1)) : 30;
        
        String playbackStartedKey = "playback_started_at:" + roundNo;
        String playbackStartedAtStr = (String) redisTemplate.opsForHash().get(sessionKey, playbackStartedKey);
        Long serverStartedAt = playbackStartedAtStr != null ? Long.parseLong(playbackStartedAtStr) : null;

        String playbackLockKey = RedisKeys.gameSessionPlaybackLockKey(lobbyCode, roundNo);
        boolean isPlaying = Boolean.TRUE.equals(redisTemplate.hasKey(playbackLockKey));

        return CurrentRoundStatusResponse.builder()
                .roundNo(roundNo)
                .status(isPlaying ? "PLAYING" : "WAITING")
                .timeLimitSeconds(timeLimitSeconds)
                .serverStartedAt(serverStartedAt)
                .build();
    }
}
