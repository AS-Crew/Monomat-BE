package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 게임 세션 Redis 키 정리(cleanup) 통합 테스트
 *
 * [검증 정책]
 * - deleteNow: base 3종 + 라운드별 6종 키를 모두 삭제한다. (로비 폭파 / 시작 롤백)
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

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS));
    }

    @Test
    @DisplayName("deleteNow는 base 3종 + 라운드별 6종 게임 세션 키를 모두 삭제한다")
    void deleteNow_removesAllGameSessionKeys() {
        // given
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> keys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        assertThat(keys).allMatch(redisTemplate::hasKey);

        // when
        gameSessionCleanupService.deleteNow(LOBBY_CODE);

        // then
        assertThat(keys).noneMatch(redisTemplate::hasKey);
    }

    @Test
    @DisplayName("expireWithGracePeriod는 존재하는 모든 키를 0<ttl<=300으로 전환한다")
    void expireWithGracePeriod_setsShortTtlOnAllKeys() {
        // given - 생성 시점처럼 2시간 TTL을 부여해 둔다
        givenFullGameSession(LOBBY_CODE, TOTAL_ROUNDS);
        List<String> keys = allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS);
        keys.forEach(key -> redisTemplate.expire(key, Duration.ofSeconds(7200)));

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
    }

    @Test
    @DisplayName("게임 세션이 없으면 정리는 예외 없이 안전하게 no-op이다")
    void cleanup_isSafeNoOpWhenSessionMissing() {
        // given - 키를 전혀 만들지 않음

        // when & then
        assertThatCode(() -> gameSessionCleanupService.deleteNow(LOBBY_CODE)).doesNotThrowAnyException();
        assertThatCode(() -> gameSessionCleanupService.expireWithGracePeriod(LOBBY_CODE)).doesNotThrowAnyException();
        assertThat(allGameSessionKeys(LOBBY_CODE, TOTAL_ROUNDS)).noneMatch(redisTemplate::hasKey);
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
     * base 3종(session/rounds/players) + 라운드별 6종을 모두 만든다.
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
            redisTemplate.opsForHash().put(RedisKeys.gameSessionRoundCorrectTimesKey(code, n), "player-1", "1000");
            redisTemplate.opsForValue().set(RedisKeys.gameSessionRoundEndedLockKey(code, n), "1");
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
            keys.add(RedisKeys.gameSessionRoundCorrectTimesKey(code, n));
            keys.add(RedisKeys.gameSessionRoundEndedLockKey(code, n));
        }
        return keys;
    }
}
