package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameLeaveEventHandler {

    private final StringRedisTemplate redisTemplate;
    private final GameRoundEndService gameRoundEndService;

    @EventListener
    public void handlePlayerLeave(PlayerLeaveEvent event) {
        String code = event.lobbyCode();
        String userIdentifier = event.userIdentifier();

        if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) {
            return;
        }

        // 1. 게임 세션 존재 여부 및 status/round_phase 검증
        String sessionKey = RedisKeys.gameSessionKey(code);
        List<Object> hashValues = redisTemplate.opsForHash().multiGet(sessionKey, List.of(RedisKeys.FIELD_STATUS, RedisKeys.FIELD_ROUND_PHASE, RedisKeys.FIELD_CURRENT_ROUND_NO));
        String status = (String) hashValues.get(0);
        String roundPhase = (String) hashValues.get(1);
        if (status == null || !"PLAYING".equals(status) || !"PLAYING".equals(roundPhase)) {
            return;
        }

        String currentRoundNoStr = (String) hashValues.get(2);
        if (currentRoundNoStr == null) {
            return;
        }
        int currentRoundNo = Integer.parseInt(currentRoundNoStr);

        // 2. 남은 참가자가 모두 정답을 맞췄는지 확인
        String participantsKey = RedisKeys.lobbyParticipantsKey(code);
        String correctPlayersKey = RedisKeys.gameSessionRoundCorrectPlayersKey(code, currentRoundNo);

        Set<String> participants = redisTemplate.opsForSet().members(participantsKey);
        if (participants == null || participants.isEmpty()) {
            return;
        }

        // 이미 나간 유저는 제외하고 체크해야 하므로, participants에서 현재 나간 userIdentifier는 제외
        participants.remove(userIdentifier);

        if (participants.isEmpty()) {
            return;
        }

        Set<String> correctPlayers = redisTemplate.opsForSet().members(correctPlayersKey);
        if (correctPlayers == null) {
            correctPlayers = Collections.emptySet();
        }

        if (correctPlayers.containsAll(participants)) {
            log.info("GameLeaveEventHandler: 이탈 발생 후 남은 모든 참가자가 정답을 맞췄습니다. 라운드를 즉시 조기 종료합니다. - code: {}, roundNo: {}", code, currentRoundNo);
            try {
                gameRoundEndService.endRound(code, currentRoundNo);
            } catch (Exception e) {
                log.error("GameLeaveEventHandler: 이탈 처리 중 라운드 조기 종료 실패 - code: {}, roundNo: {}", code, currentRoundNo, e);
            }
        }
    }
}
