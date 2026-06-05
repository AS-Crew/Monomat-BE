package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.game.config.GameSessionProperties;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapPlayCountServiceTest {

    private static final String LOBBY_CODE = "ABC1234";
    private static final Long MAP_ID = 1L;
    private static final Duration PLAY_COUNT_DEDUP_TTL = Duration.ofHours(2);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private QuizMapJpaRepository quizMapJpaRepository;

    @Mock
    private MapCacheEvictor mapCacheEvictor;

    @Mock
    private GameSessionProperties gameSessionProperties;

    @InjectMocks
    private MapPlayCountService mapPlayCountService;

    @Test
    @DisplayName("최초 집계이면 Redis SETNX 성공 후 playCount를 증가시키고 커밋 이후 캐시를 무효화한다")
    void countOnce_firstCount_increasesPlayCountAndEvictsCacheAfterCommit() {
        // given
        String countedKey = RedisKeys.lobbyMapPlayCountedKey(LOBBY_CODE);

        TransactionSynchronizationManager.initSynchronization();
        try {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(gameSessionProperties.getRedisTtl()).thenReturn(PLAY_COUNT_DEDUP_TTL);
            when(valueOperations.setIfAbsent(
                    eq(countedKey),
                    eq(String.valueOf(MAP_ID)),
                    eq(PLAY_COUNT_DEDUP_TTL)
            )).thenReturn(true);
            when(quizMapJpaRepository.increasePlayCount(MAP_ID)).thenReturn(1);

            // when
            mapPlayCountService.countOnce(LOBBY_CODE, MAP_ID);

            // then
            verify(quizMapJpaRepository).increasePlayCount(MAP_ID);
            verify(mapCacheEvictor, never()).evictPublicMapCaches(MAP_ID);
            verify(redisTemplate, never()).delete(countedKey);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            verify(mapCacheEvictor).evictPublicMapCaches(MAP_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("이미 집계된 로비이면 playCount를 다시 증가시키지 않는다")
    void countOnce_alreadyCounted_doesNotIncreasePlayCount() {
        // given
        String countedKey = RedisKeys.lobbyMapPlayCountedKey(LOBBY_CODE);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(gameSessionProperties.getRedisTtl()).thenReturn(PLAY_COUNT_DEDUP_TTL);
        when(valueOperations.setIfAbsent(
                eq(countedKey),
                eq(String.valueOf(MAP_ID)),
                eq(PLAY_COUNT_DEDUP_TTL)
        )).thenReturn(false);

        // when
        mapPlayCountService.countOnce(LOBBY_CODE, MAP_ID);

        // then
        verify(quizMapJpaRepository, never()).increasePlayCount(any());
        verify(mapCacheEvictor, never()).evictPublicMapCaches(any());
        verify(redisTemplate, never()).delete(countedKey);
    }

    @Test
    @DisplayName("DB 증가 대상 맵이 없으면 Redis 중복 방지 키를 삭제하고 예외를 던진다")
    void countOnce_mapNotFound_deletesDedupKeyAndThrowsException() {
        // given
        String countedKey = RedisKeys.lobbyMapPlayCountedKey(LOBBY_CODE);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(gameSessionProperties.getRedisTtl()).thenReturn(PLAY_COUNT_DEDUP_TTL);
        when(valueOperations.setIfAbsent(
                eq(countedKey),
                eq(String.valueOf(MAP_ID)),
                eq(PLAY_COUNT_DEDUP_TTL)
        )).thenReturn(true);
        when(quizMapJpaRepository.increasePlayCount(MAP_ID)).thenReturn(0);

        // when & then
        assertThatThrownBy(() -> mapPlayCountService.countOnce(LOBBY_CODE, MAP_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("맵 플레이 횟수 증가 대상이 존재하지 않습니다. mapId=" + MAP_ID);

        verify(redisTemplate).delete(countedKey);
        verify(mapCacheEvictor, never()).evictPublicMapCaches(any());
    }

    @Test
    @DisplayName("DB 증가 중 예외가 발생하면 Redis 중복 방지 키를 삭제하고 예외를 전파한다")
    void countOnce_increaseFails_deletesDedupKeyAndRethrowsException() {
        // given
        String countedKey = RedisKeys.lobbyMapPlayCountedKey(LOBBY_CODE);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(gameSessionProperties.getRedisTtl()).thenReturn(PLAY_COUNT_DEDUP_TTL);
        when(valueOperations.setIfAbsent(
                eq(countedKey),
                eq(String.valueOf(MAP_ID)),
                eq(PLAY_COUNT_DEDUP_TTL)
        )).thenReturn(true);
        when(quizMapJpaRepository.increasePlayCount(MAP_ID))
                .thenThrow(new IllegalStateException("DB update failed"));

        // when & then
        assertThatThrownBy(() -> mapPlayCountService.countOnce(LOBBY_CODE, MAP_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB update failed");

        verify(redisTemplate).delete(countedKey);
        verify(mapCacheEvictor, never()).evictPublicMapCaches(any());
    }

    @Test
    @DisplayName("lobbyCode가 비어 있으면 IllegalArgumentException을 던진다")
    void countOnce_blankLobbyCode_throwsException() {
        assertThatThrownBy(() -> mapPlayCountService.countOnce(" ", MAP_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lobbyCode must not be blank");

        verify(redisTemplate, never()).opsForValue();
        verify(quizMapJpaRepository, never()).increasePlayCount(any());
    }

    @Test
    @DisplayName("mapId가 null이면 IllegalArgumentException을 던진다")
    void countOnce_nullMapId_throwsException() {
        assertThatThrownBy(() -> mapPlayCountService.countOnce(LOBBY_CODE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mapId must not be null");

        verify(redisTemplate, never()).opsForValue();
        verify(quizMapJpaRepository, never()).increasePlayCount(any());
    }

    @Test
    @DisplayName("트랜잭션 롤백 시 Redis 중복 방지 키를 삭제하고 캐시는 무효화하지 않는다")
    void countOnce_transactionRollback_deletesDedupKeyAndDoesNotEvictCache() {
        // given
        String countedKey = RedisKeys.lobbyMapPlayCountedKey(LOBBY_CODE);

        TransactionSynchronizationManager.initSynchronization();
        try {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(gameSessionProperties.getRedisTtl()).thenReturn(PLAY_COUNT_DEDUP_TTL);
            when(valueOperations.setIfAbsent(
                    eq(countedKey),
                    eq(String.valueOf(MAP_ID)),
                    eq(PLAY_COUNT_DEDUP_TTL)
            )).thenReturn(true);
            when(quizMapJpaRepository.increasePlayCount(MAP_ID)).thenReturn(1);

            // when
            mapPlayCountService.countOnce(LOBBY_CODE, MAP_ID);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK
                    ));

            // then
            verify(redisTemplate).delete(countedKey);
            verify(mapCacheEvictor, never()).evictPublicMapCaches(MAP_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}