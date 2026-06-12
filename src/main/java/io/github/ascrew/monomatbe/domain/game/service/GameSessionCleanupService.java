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
 * 원자적으로 정리한다. (base 3종 + 라운드별 9종)
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
 *
 * [집계 정책]
 * 스크립트 반환값(처리한 키 수)을 파싱해 성공(>=1) / no-op(0) / 실패(null·파싱불가)를
 * 구분 집계한다. no-op은 정상 종료(EXPIRE) 경로에서는 이상 신호이므로 별도 metric/로그로 분리한다.
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

            recordOutcome(lobbyCode, mode, processed);
        } catch (Exception e) {
            incrementMetricQuietly(failedMetricFor(mode));
            log.error("[MONITORING_REQUIRED] 게임 세션 Redis 키 정리 실패 - 2시간 TTL 자동 만료에 의존. "
                    + "code: {}, mode: {}", lobbyCode, mode, e);
        }
    }

    /**
     * 정리 모드에 따라 실패 metric 키를 선택한다.
     * DELETE 실패(orphan 잔존)와 EXPIRE 실패(grace period 누락)는 영향도가 달라 분리 집계한다.
     */
    private String failedMetricFor(String mode) {
        return MODE_DELETE.equals(mode)
                ? RedisKeys.METRIC_GAME_SESSION_CLEANUP_DELETE_FAILED
                : RedisKeys.METRIC_GAME_SESSION_CLEANUP_EXPIRE_FAILED;
    }

    /**
     * 스크립트 반환값(처리한 키 수)을 파싱해 성공 / no-op / 실패를 구분 집계한다.
     *
     * <ul>
     *   <li>{@code processed >= 1} : 성공 — SUCCESS metric + info 로그</li>
     *   <li>{@code processed == 0} : 정리 대상 없음(no-op) — NOOP metric.
     *       EXPIRE(정상 종료)에서는 세션이 이미 사라진 이상 신호이므로 warn, DELETE는 info.</li>
     *   <li>{@code null}·파싱 불가 : 실패로 간주 — FAILED metric + [MONITORING_REQUIRED] 로그</li>
     * </ul>
     */
    private void recordOutcome(String lobbyCode, String mode, String processed) {
        Long count = parseProcessed(processed);
        if (count == null) {
            incrementMetricQuietly(failedMetricFor(mode));
            log.error("[MONITORING_REQUIRED] 게임 세션 Redis 키 정리 반환값 파싱 불가 - 실패로 간주. "
                    + "code: {}, mode: {}, processed: {}", lobbyCode, mode, processed);
            return;
        }

        if (count >= 1) {
            incrementMetricQuietly(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS);
            log.info("게임 세션 Redis 키 정리 완료 - code: {}, mode: {}, processedKeys: {}",
                    lobbyCode, mode, count);
            return;
        }

        // count == 0 → 정리 대상 키 없음(no-op)
        incrementMetricQuietly(RedisKeys.METRIC_GAME_SESSION_CLEANUP_NOOP);
        if (MODE_EXPIRE.equals(mode)) {
            log.warn("게임 세션 정리 대상 키 없음(no-op) - 정상 종료 경로에서 이상 신호. code: {}", lobbyCode);
        } else {
            log.info("게임 세션 정리 대상 키 없음(no-op) - code: {}, mode: {}", lobbyCode, mode);
        }
    }

    /**
     * 스크립트가 반환한 처리 키 수 문자열을 long으로 파싱한다.
     * null이거나 숫자로 파싱할 수 없으면 null을 반환해 호출부가 실패로 처리하게 한다.
     */
    private Long parseProcessed(String processed) {
        if (processed == null) {
            return null;
        }
        try {
            return Long.parseLong(processed.trim());
        } catch (NumberFormatException e) {
            return null;
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
