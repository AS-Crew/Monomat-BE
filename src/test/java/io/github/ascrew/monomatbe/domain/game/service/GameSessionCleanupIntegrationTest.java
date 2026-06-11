package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 게임 세션 Redis 키 정리(cleanup) 통합 테스트
 *
 * [검증 정책]
 * - deleteNow: base 3종 + 라운드별 9종 키를 모두 삭제한다. (로비 폭파 / 시작 롤백)
 * - expireWithGracePeriod: 존재하는 모든 키를 0<ttl<=300 으로 전환한다. (정상 종료)
 * - 게임 세션이 없을 때 정리는 예외 없이 안전 no-op이다.
 * - 로비 폭파 이벤트(LobbyClosedEvent) 수신 시 게임 세션 키가 정리된다. (stale game session 방지)
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class GameSessionCleanupIntegrationTest {

    private static final String LOBBY_CODE = "CLEAN1";
    private static final int TOTAL_ROUNDS = 3;

    @Autowired
    private GameSessionCleanupService gameSessionCleanupService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("cleanupGameSessionScript")
    private RedisScript<String> cleanupGameSessionScript;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS));
        redisTemplate.delete(List.of(
                RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS,
                RedisKeys.METRIC_GAME_SESSION_CLEANUP_DELETE_FAILED,
                RedisKeys.METRIC_GAME_SESSION_CLEANUP_EXPIRE_FAILED,
                RedisKeys.METRIC_GAME_SESSION_CLEANUP_NOOP));
    }

    /** metric 카운터 현재값을 읽는다. (미설정 시 0) */
    private long metricCount(String metricKey) {
        String value = redisTemplate.opsForValue().get(metricKey);
        return value == null ? 0L : Long.parseLong(value);
    }

    @Test
    @DisplayName("deleteNow는 base 3종 + 라운드별 9종 게임 세션 키를 모두 삭제한다")
    void deleteNow_removesAllGameSessionKeys() {
        // given
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> keys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        assertThat(keys).allMatch(redisTemplate::hasKey);

        long successBefore = metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS);

        // when
        gameSessionCleanupService.deleteNow(LOBBY_CODE);

        // then
        assertThat(keys).noneMatch(redisTemplate::hasKey);
        assertThat(metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS))
                .isEqualTo(successBefore + 1);
    }

    @Test
    @DisplayName("cleanup Lua는 RedisKeys가 생성하는 모든 게임 세션 키를 빠짐없이 정리한다 (키 계약 고정)")
    void cleanupScript_deletesExactlyAllRedisKeysGameSessionKeys() {
        // given - RedisKeys 팩토리로 만드는 base 3종 + 라운드별 9종을 모두 생성
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> expectedKeys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        String sessionKey = RedisKeys.gameSessionKey(LOBBY_CODE);

        // when - 스크립트를 직접 실행해 처리 키 수(processed) 반환값을 확인한다
        String processed = redisTemplate.execute(
                cleanupGameSessionScript, List.of(sessionKey), "DELETE", "0");

        // then - RedisKeys가 만든 모든 키가 삭제되고, Lua가 정리한 키 수가 정확히 일치한다.
        // (RedisKeys suffix 변경/신규 라운드 키 추가 시 allGameSessionKeys와 Lua roundSuffixes 불일치를 잡는다.
        //  새 라운드 키 메서드를 RedisKeys에 추가하면 allGameSessionKeys에도 반드시 추가해야 한다.)
        assertThat(expectedKeys).noneMatch(redisTemplate::hasKey);
        assertThat(processed).isEqualTo(String.valueOf(expectedKeys.size()));
    }

    @Test
    @DisplayName("expireWithGracePeriod는 존재하는 모든 키를 0<ttl<=300으로 전환한다")
    void expireWithGracePeriod_setsShortTtlOnAllKeys() {
        // given - 생성 시점처럼 2시간 TTL을 부여해 둔다
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> keys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        keys.forEach(key -> redisTemplate.expire(key, Duration.ofSeconds(7200)));
        long successBefore = metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS);

        // when
        gameSessionCleanupService.expireWithGracePeriod(LOBBY_CODE);

        // then
        for (String key : keys) {
            Long ttl = redisTemplate.getExpire(key);
            assertThat(ttl)
                    .as("key=%s TTL", key)
                    .isNotNull()
                    .isGreaterThan(0L)
                    .isLessThanOrEqualTo(300L);
        }
        assertThat(metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS))
                .isEqualTo(successBefore + 1);
    }

    @Test
    @DisplayName("EXPIRE 모드에서 ttlSeconds < 1이면 즉시만료 없이 스크립트가 fail-fast한다")
    void expireScript_failsFastOnInvalidTtl() {
        // given - 생성 시점처럼 2시간 TTL을 부여해 둔다
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> keys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        keys.forEach(key -> redisTemplate.expire(key, Duration.ofSeconds(7200)));
        String sessionKey = RedisKeys.gameSessionKey(LOBBY_CODE);

        // when & then - ttl="0"(즉시만료 위험)/""(nil)은 스크립트가 실패해야 한다
        assertThatThrownBy(() -> redisTemplate.execute(
                cleanupGameSessionScript, List.of(sessionKey), "EXPIRE", "0"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> redisTemplate.execute(
                cleanupGameSessionScript, List.of(sessionKey), "EXPIRE", ""))
                .isInstanceOf(Exception.class);

        // 키가 즉시 만료되지 않고 원래 TTL(2시간)을 유지한다
        for (String key : keys) {
            Long ttl = redisTemplate.getExpire(key);
            assertThat(ttl)
                    .as("key=%s TTL", key)
                    .isNotNull()
                    .isGreaterThan(300L);
        }
    }

    @Test
    @DisplayName("게임 세션이 없으면 정리는 예외 없이 no-op metric으로 집계되고 success는 오르지 않는다")
    void cleanup_isSafeNoOpWhenSessionMissing() {
        // given - 키를 전혀 만들지 않음
        long noopBefore = metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_NOOP);
        long successBefore = metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS);

        // when & then
        assertThatCode(() -> gameSessionCleanupService.deleteNow(LOBBY_CODE)).doesNotThrowAnyException();
        assertThatCode(() -> gameSessionCleanupService.expireWithGracePeriod(LOBBY_CODE)).doesNotThrowAnyException();
        assertThat(allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS)).noneMatch(redisTemplate::hasKey);

        // deleteNow + expireWithGracePeriod 두 번 모두 정리 대상이 없어 NOOP만 2회 증가한다
        assertThat(metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_NOOP))
                .isEqualTo(noopBefore + 2);
        assertThat(metricCount(RedisKeys.METRIC_GAME_SESSION_CLEANUP_SUCCESS))
                .isEqualTo(successBefore);
    }

    @Test
    @DisplayName("로비 폭파 이벤트(LobbyClosedEvent) 수신 시 게임 세션 키가 정리된다")
    void lobbyClosedEvent_cleansUpStaleGameSession() {
        // given - 게임 도중 전원 퇴장으로 로비가 폭파된 상황
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> keys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        assertThat(keys).anyMatch(redisTemplate::hasKey);

        // when - @EventListener는 동기 실행되므로 발행 직후 정리가 완료된다
        eventPublisher.publishEvent(new LobbyClosedEvent(LOBBY_CODE));

        // then
        assertThat(keys).noneMatch(redisTemplate::hasKey);
    }

    /**
     * 실제 게임 진행 중 생성되는 모든 게임 세션 키를 채운다.
     * base 3종(session/rounds/players) + 라운드별 9종을 모두 만든다.
     */
    private void givenFullGameSession(String code, int totalRounds) {
        String sessionKey = RedisKeys.gameSessionKey(code);
        redisTemplate.opsForHash().put(sessionKey, "status", "PLAYING");
        redisTemplate.opsForHash().put(sessionKey, "current_round_no", String.valueOf(totalRounds));
        redisTemplate.opsForHash().put(sessionKey, "total_question_count", String.valueOf(totalRounds));

        String roundsKey = RedisKeys.gameSessionRoundsKey(code);
        for (int n = 1; n <= totalRounds; n++) {
            redisTemplate.opsForList().rightPush(roundsKey, String.valueOf(n));
        }

        redisTemplate.opsForHash().put(RedisKeys.gameSessionPlayersKey(code), "player-1", "0");

        for (int n = 1; n <= totalRounds; n++) {
            redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundReadyKey(code, n), "player-1");
            redisTemplate.opsForValue().set(RedisKeys.gameSessionPlaybackLockKey(code, n), "1");
            redisTemplate.opsForHash().put(RedisKeys.gameSessionRoundDataKey(code, n), "title", "song-" + n);
            redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundCorrectPlayersKey(code, n), "player-1");
            redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundSkipVotesKey(code, n), "player-1");
            redisTemplate.opsForSet().add(RedisKeys.gameSessionRoundPlaybackErrorsKey(code, n), "player-1");
            redisTemplate.opsForHash().put(RedisKeys.gameSessionRoundCorrectTimesKey(code, n), "player-1", "1000");
            redisTemplate.opsForValue().set(RedisKeys.gameSessionRoundEndedLockKey(code, n), "1");
            redisTemplate.opsForValue().set(RedisKeys.gameSessionNextRoundLockKey(code, n), "1");
        }
    }

    private List<String> allGameSessionKeys(String code, int totalRounds) {
        List<String> keys = new ArrayList<>();
        keys.add(RedisKeys.gameSessionKey(code));
        keys.add(RedisKeys.gameSessionRoundsKey(code));
        keys.add(RedisKeys.gameSessionPlayersKey(code));
        for (int n = 1; n <= totalRounds; n++) {
            keys.add(RedisKeys.gameSessionRoundReadyKey(code, n));
            keys.add(RedisKeys.gameSessionPlaybackLockKey(code, n));
            keys.add(RedisKeys.gameSessionRoundDataKey(code, n));
            keys.add(RedisKeys.gameSessionRoundCorrectPlayersKey(code, n));
            keys.add(RedisKeys.gameSessionRoundSkipVotesKey(code, n));
            keys.add(RedisKeys.gameSessionRoundPlaybackErrorsKey(code, n));
            keys.add(RedisKeys.gameSessionRoundCorrectTimesKey(code, n));
            keys.add(RedisKeys.gameSessionRoundEndedLockKey(code, n));
            keys.add(RedisKeys.gameSessionNextRoundLockKey(code, n));
        }
        return keys;
    }
}
