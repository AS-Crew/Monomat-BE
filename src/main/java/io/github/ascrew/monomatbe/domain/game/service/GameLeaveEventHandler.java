package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.event.PlayerLeaveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class GameLeaveEventHandler {

    private final StringRedisTemplate redisTemplate;
    private final GameSkipVoteService gameSkipVoteService;

    @EventListener
    public void handlePlayerLeave(PlayerLeaveEvent event) {
        try {
            handlePlayerLeaveSafely(event);
        } catch (RuntimeException e) {
            log.warn("인게임 퇴장 후처리 실패 - code: {}, user: {}",
                    event != null ? event.lobbyCode() : null,
                    event != null ? event.userIdentifier() : null,
                    e);
        }
    }

    private void handlePlayerLeaveSafely(PlayerLeaveEvent event) {
        if (event == null) {
            return;
        }

        String code = event.lobbyCode();
        String userIdentifier = event.userIdentifier();

        if (!StringUtils.hasText(code) || !StringUtils.hasText(userIdentifier)) {
            return;
        }

        // 1. 게임 세션 존재 여부 및 status/round_phase 검증
        String sessionKey = RedisKeys.gameSessionKey(code);
        List<Object> hashValues = redisTemplate.opsForHash().multiGet(sessionKey, List.of(
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_ROUND_PHASE,
                RedisKeys.FIELD_CURRENT_ROUND_NO
        ));
        if (hashValues == null || hashValues.size() < 3) {
            log.warn("게임 세션 상태 조회 실패 - code: {}", code);
            return;
        }

        String status = (String) hashValues.get(0);
        String roundPhase = (String) hashValues.get(1);
        if (status == null || !"PLAYING".equals(status) || !"PLAYING".equals(roundPhase)) {
            return;
        }

        String currentRoundNoStr = (String) hashValues.get(2);
        if (currentRoundNoStr == null) {
            return;
        }

        int currentRoundNo;
        try {
            currentRoundNo = Integer.parseInt(currentRoundNoStr);
        } catch (NumberFormatException e) {
            log.warn("현재 라운드 번호 파싱 실패 - code: {}, value: {}", code, currentRoundNoStr);
            return;
        }

        gameSkipVoteService.removeParticipantRoundSignals(code, userIdentifier, currentRoundNo);
        gameSkipVoteService.reevaluateSkipThresholds(code, currentRoundNo);
    }
}
