package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.dto.CurrentRoundStatusResponse;
import io.github.ascrew.monomatbe.domain.lobby.LobbyUserAccessStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class GameSessionQueryService {

    private final StringRedisTemplate redisTemplate;
    private final LobbyRepository lobbyRepository;
    private final GameRoundEndService gameRoundEndService;
    private final GameRoundProgressService gameRoundProgressService;
    private final MapItemJpaRepository mapItemJpaRepository;

    @Lazy
    public GameSessionQueryService(
            StringRedisTemplate redisTemplate,
            LobbyRepository lobbyRepository,
            @Lazy GameRoundEndService gameRoundEndService,
            @Lazy GameRoundProgressService gameRoundProgressService,
            @Lazy MapItemJpaRepository mapItemJpaRepository) {
        this.redisTemplate = redisTemplate;
        this.lobbyRepository = lobbyRepository;
        this.gameRoundEndService = gameRoundEndService;
        this.gameRoundProgressService = gameRoundProgressService;
        this.mapItemJpaRepository = mapItemJpaRepository;
    }

    public CurrentRoundStatusResponse getCurrentRoundStatus(String lobbyCode, String userIdentifier) {
        LobbyUserAccessStatus accessStatus = lobbyRepository.getUserAccessStatus(lobbyCode, userIdentifier);
        switch (accessStatus) {
            case LOBBY_NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 로비입니다.");
            case KICKED -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "강퇴된 로비의 게임 상태는 조회할 수 없습니다.");
            case NOT_PARTICIPANT -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "로비 참여자만 게임 상태를 조회할 수 있습니다.");
            case PARTICIPANT -> {} // 통과
        }

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);
        
        List<Object> hashValues = redisTemplate.opsForHash().multiGet(sessionKey, List.of(
                RedisKeys.FIELD_CURRENT_ROUND_NO,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE
        ));
        
        if (hashValues.get(0) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "진행 중인 게임 세션이 없습니다.");
        }

        int roundNo = Integer.parseInt((String) hashValues.get(0));
        int timeLimitSeconds = hashValues.get(1) != null ? Integer.parseInt((String) hashValues.get(1)) : 30;
        String redisStatus = hashValues.get(2) != null ? (String) hashValues.get(2) : "PLAYING";
        String roundPhase = hashValues.get(3) != null ? (String) hashValues.get(3) : "READY";
        
        String playbackStartedKey = RedisKeys.gameSessionRoundPlaybackStartedAtField(roundNo);
        String playbackStartedAtStr = (String) redisTemplate.opsForHash().get(sessionKey, playbackStartedKey);
        Long serverStartedAt = playbackStartedAtStr != null ? Long.parseLong(playbackStartedAtStr) : null;

        String playbackLockKey = RedisKeys.gameSessionPlaybackLockKey(lobbyCode, roundNo);
        boolean isPlaying = Boolean.TRUE.equals(redisTemplate.hasKey(playbackLockKey));

        // 타이머 유실 대응 복구 메커니즘 (Self-Healing)
        if ("PLAYING".equals(redisStatus) && "PLAYING".equals(roundPhase) && serverStartedAt != null) {
            long elapsed = System.currentTimeMillis() - serverStartedAt;
            long limitTimeMillis = timeLimitSeconds * 1000L + 1500L;
            
            if (elapsed >= limitTimeMillis) {
                // 시간 초과가 이미 발생했으나 라운드가 미종료 상태인 경우 -> 즉시 조기 종료 처리
                log.warn("getCurrentRoundStatus: 타이머 유실 감지 - 시간 초과로 라운드를 즉시 종료합니다. code: {}, roundNo: {}", lobbyCode, roundNo);
                try {
                    gameRoundEndService.endRound(lobbyCode, roundNo);
                } catch (Exception e) {
                    log.error("getCurrentRoundStatus: 타이머 유실 복구 중 라운드 종료 실패", e);
                }
                isPlaying = false;
                roundPhase = "ENDED";
            } else {
                // 아직 제한 시간 이전이나 스케줄러에 등록되어 있지 않은 경우 -> 타이머 재등록
                try {
                    if (!gameRoundProgressService.isRoundEndScheduled(lobbyCode, roundNo)) {
                        long remainingDelay = limitTimeMillis - elapsed;
                        gameRoundProgressService.rescheduleRoundEnd(lobbyCode, roundNo, remainingDelay);
                    }
                } catch (Exception e) {
                    log.error("getCurrentRoundStatus: 타이머 유실 복구 중 스케줄링 실패", e);
                }
            }
        }

        // status는 코드와 이전 이슈 기준을 맞추기 위해 isPlaying 상태에 따라 PLAYING/WAITING으로 매핑하여 반환하되,
        // FINISHED이거나 ENDED/READY 인 경우를 구분합니다.
        String responseStatus;
        if ("FINISHED".equals(redisStatus) || "FINISHED".equals(roundPhase)) {
            responseStatus = "FINISHED";
        } else if ("ENDED".equals(roundPhase) || "ENDED".equals(redisStatus)) {
            responseStatus = "WAITING"; // 라운드 종료 상태(결과화면 대기)
        } else if ("READY".equals(roundPhase) || "READY".equals(redisStatus)) {
            responseStatus = "WAITING"; // 라운드 대기 상태
        } else {
            responseStatus = isPlaying ? "PLAYING" : "WAITING";
        }

        // --- 재접속 복구용 추가 데이터 바인딩 ---
        String videoId = null;
        String youtubeUrl = null;
        Integer startTime = null;
        Integer endTime = null;
        Integer remainingSeconds = null;
        boolean isCorrect = false;

        if ("READY".equals(roundPhase) || "PLAYING".equals(roundPhase)) {
            // 1. 현재 라운드 비디오 정보 복구
            String roundsKey = RedisKeys.gameSessionRoundsKey(lobbyCode);
            String mapItemIdStr = redisTemplate.opsForList().index(roundsKey, roundNo - 1);
            if (mapItemIdStr != null) {
                try {
                    Long mapItemId = Long.parseLong(mapItemIdStr);
                    Optional<MapItem> mapItemOpt = mapItemJpaRepository.findById(mapItemId);
                    if (mapItemOpt.isPresent()) {
                        MapItem mapItem = mapItemOpt.get();
                        videoId = mapItem.getVideoId();
                        youtubeUrl = mapItem.getYoutubeUrl();
                        startTime = mapItem.getStartTime();
                        endTime = mapItem.getStartTime() + timeLimitSeconds;
                    }
                } catch (Exception e) {
                    log.error("getCurrentRoundStatus: MapItem 조회 중 예외 발생 - code: {}, roundNo: {}, mapItemIdStr: {}",
                            lobbyCode, roundNo, mapItemIdStr, e);
                }
            }

            // 2. 이미 정답을 제출하여 맞췄는지 여부 판별
            String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(lobbyCode, roundNo);
            isCorrect = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(correctPlayersKey, userIdentifier));

            // 3. 실제 재생 시작 시각 기준 남은 시간 계산
            if ("PLAYING".equals(roundPhase) && serverStartedAt != null) {
                long elapsed = System.currentTimeMillis() - serverStartedAt;
                long remainingMillis = (timeLimitSeconds * 1000L) - elapsed;
                remainingSeconds = (int) Math.max(0, (remainingMillis + 999) / 1000);
            }
        }

        return CurrentRoundStatusResponse.builder()
                .roundNo(roundNo)
                .status(responseStatus)
                .roundPhase(roundPhase)
                .timeLimitSeconds(timeLimitSeconds)
                .serverStartedAt(serverStartedAt)
                .videoId(videoId)
                .youtubeUrl(youtubeUrl)
                .startTime(startTime)
                .endTime(endTime)
                .remainingSeconds(remainingSeconds)
                .isCorrect(isCorrect)
                .build();
    }
}
