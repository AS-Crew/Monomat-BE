package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 게임 세션 Redis 키 정리를 담당하는 서비스
 *
 * [책임]
 * cleanup_game_session.lua를 실행하여 하나의 로비 코드에 속한 모든 게임 세션 키를
 * 원자적으로 정리한다. (base 3종 + 라운드별 6종)
 *
 * [정리 시점]
 * - 게임 정상 종료      : expireWithGracePeriod() — 300초 짧은 TTL 전환
 * - 로비 폭파(전원 퇴장) : deleteNow() — 즉시 삭제
 * - 게임 시작 DB 롤백   : deleteNow() — 즉시 삭제 (보상)
 *
 * [실패 정책 — 경량 reconciliation]
 * 모든 게임 세션 키는 생성 시 2시간 TTL을 가지므로 정리 실패는 치명적이지 않다.
 * 정리 실패 시 [MONITORING_REQUIRED] 로그와 실패 metric만 남기고 예외를 전파하지 않으며,
 * 2시간 TTL을 최종 안전망으로 삼는다. (별도 재처리 스케줄러를 두지 않는다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameSessionCleanupService {

    private static final String MODE_DELETE = "DELETE";
    private static final String MODE_EXPIRE = "EXPIRE";

    /** 게임 정상 종료 후 클라이언트 재조회를 위한 grace period (초) */
    private static final int GRACE_PERIOD_SECONDS = 300;

    private final StringRedisTemplate stringRedisTemplate;

    @Qualifier("cleanupGameSessionScript")
    private final RedisScript<String> cleanupGameSessionScript;

    /**
     * 게임 정상 종료 시 게임 세션 키를 짧은 TTL(grace period)로 전환한다.
     *
     * 최종 점수/랭킹은 DB에 영구 저장되므로, Redis 키는 종료 직후 재조회 여유만 두고 만료된다.
     *
     * @param lobbyCode 로비 초대 코드
     */
    public void expireWithGracePeriod(String lobbyCode) {
        cleanup(lobbyCode, MODE_EXPIRE, String.valueOf(GRACE_PERIOD_SECONDS));
    }

    /**
     * 게임 세션 키를 즉시 삭제한다. (로비 폭파 / 게임 시작 DB 롤백 보상)
     *
     * 게임 세션 키가 없으면 Lua가 안전하게 no-op 처리한다.
     *
     * @param lobbyCode 로비 초대 코드
     */
    public void deleteNow(String lobbyCode) {
        cleanup(lobbyCode, MODE_DELETE, "0");
    }

    private void cleanup(String lobbyCode, String mode, String ttlSeconds) {
        if (!StringUtils.hasText(lobbyCode)) {
            return;
        }

        String sessionKey = RedisKeys.gameSessionKey(lobbyCode);

        try {
            String processed = stringRedisTemplate.execute(
                    cleanupGameSessionScript,
                    List.of(sessionKey),
                    mode,
                    ttlSeconds
            );

            incrementMetricQuietly(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS);
            log.info("게임 세션 Redis 키 정리 완료 - code: {}, mode: {}, processedKeys: {}",
                    lobbyCode, mode, processed);
        } catch (Exception e) {
            incrementMetricQuietly(RedisKeys.METRIC_GAME_SESSION_CLEANUP_FAILED);
            log.error("[MONITORING_REQUIRED] 게임 세션 Redis 키 정리 실패 - 2시간 TTL 자동 만료에 의존. "
                    + "code: {}, mode: {}", lobbyCode, mode, e);
        }
    }

    /**
     * 메트릭 카운터를 증가시키되, 실패 시에도 예외를 전파하지 않는다.
     *
     * 정리 실패의 주요 원인은 Redis 장애인데, 같은 Redis로 메트릭을 증가시키면
     * 동일한 예외가 다시 발생한다. 이 메서드는 cleanup()의 "예외 미전파" 보장을 지키기 위해
     * 메트릭 증가 자체의 실패를 흡수한다. (2시간 TTL이 최종 안전망)
     */
    private void incrementMetricQuietly(String metricKey) {
        try {
            stringRedisTemplate.opsForValue().increment(metricKey);
        } catch (Exception e) {
            log.warn("게임 세션 정리 메트릭 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}
