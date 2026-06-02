package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.game.repository.GameSessionJpaRepository;
import io.github.ascrew.monomatbe.domain.game.repository.redis.GameRoundRecoveryRepository;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameRoundProgressService 멱등 락 동작 검증.
 *
 * 인메모리 예약과 복구 워커가 동시에 startNextRound를 호출해도, next_round_lock SETNX 락을
 * 획득하지 못한 호출은 라운드를 진행시키지 않고 즉시 종료해야 한다. (라운드 스킵 방지)
 */
class GameRoundProgressServiceTest {

    private static final String CODE = "ABC123";
    private static final int NEXT_ROUND = 2;

    @Test
    @DisplayName("next_round 멱등 락 획득에 실패하면 DB 조회 없이 즉시 종료한다")
    @SuppressWarnings("unchecked")
    void startNextRound_skipsWhenLockNotAcquired() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String lockKey = RedisKeys.gameSessionRoundNextLockKey(CODE, NEXT_ROUND);
        when(valueOperations.setIfAbsent(eq(lockKey), eq("1"), any(Duration.class))).thenReturn(false);

        GameSessionJpaRepository gameSessionJpaRepository = mock(GameSessionJpaRepository.class);

        GameRoundProgressService service = new GameRoundProgressService(
                mock(TaskScheduler.class),
                redisTemplate,
                gameSessionJpaRepository,
                mock(MapItemJpaRepository.class),
                mock(GameRealtimeNotifier.class),
                mock(ApplicationContext.class),
                mock(GameRoundRecoveryRepository.class)
        );

        service.startNextRound(CODE, NEXT_ROUND);

        // 락 미획득 → 라운드 진행 로직(세션 조회/갱신)에 진입하지 않는다.
        verify(gameSessionJpaRepository, never()).findActiveSessionByLobbyCode(anyString());
    }
}
