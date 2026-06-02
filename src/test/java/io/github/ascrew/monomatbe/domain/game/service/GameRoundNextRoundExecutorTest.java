package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameRoundNextRoundExecutor 멱등 락 동작 검증.
 *
 * 인메모리 예약과 복구(GameRoundStallRecoveryService/GameRoundRecoveryService)가 동시에
 * startNextRound를 호출해도, next_lock SETNX 락을 획득하지 못한 호출은 라운드를 진행시키지 않고
 * 즉시 종료해야 한다. (라운드 스킵 방지)
 */
class GameRoundNextRoundExecutorTest {

    private static final String CODE = "ABC123";
    private static final int NEXT_ROUND = 2;
    private static final String SESSION_KEY = RedisKeys.gameSessionKey(CODE);

    @Test
    @DisplayName("next 멱등 락 획득에 실패하면 DB 조회 없이 즉시 종료한다")
    @SuppressWarnings("unchecked")
    void startNextRound_skipsWhenLockNotAcquired() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 1차 Redis 완료 검증 통과(현재 라운드 정보 없음)
        when(hashOperations.get(SESSION_KEY, RedisKeys.FIELD_CURRENT_ROUND_NO)).thenReturn(null);

        // 처리 중 락 획득 실패
        String lockKey = RedisKeys.gameSessionNextRoundLockKey(CODE, NEXT_ROUND);
        when(valueOperations.setIfAbsent(eq(lockKey), eq("1"), any(Duration.class))).thenReturn(false);

        GameSessionJpaRepository gameSessionJpaRepository = mock(GameSessionJpaRepository.class);

        GameRoundNextRoundExecutor executor = new GameRoundNextRoundExecutor(
                gameSessionJpaRepository,
                mock(MapItemJpaRepository.class),
                mock(GameRealtimeNotifier.class),
                redisTemplate,
                mock(GameRoundStartService.class)
        );

        executor.startNextRound(CODE, NEXT_ROUND);

        // 락 미획득 → 라운드 진행 로직(세션 조회/갱신)에 진입하지 않는다.
        verify(gameSessionJpaRepository, never()).findActiveSessionByLobbyCode(anyString());
    }
}
