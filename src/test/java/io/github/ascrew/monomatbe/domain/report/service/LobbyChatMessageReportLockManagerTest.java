package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyChatMessageReportLockManagerTest {

    private static final Long REPORTER_ID = 1L;
    private static final Long LOBBY_ID = 10L;
    private static final String MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
    private static final String LOCK_KEY = RedisKeys.lobbyChatMessageReportLockKey(
            REPORTER_ID,
            LOBBY_ID,
            MESSAGE_ID
    );

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private LobbyChatMessageReportLockManager lockManager;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lockManager = new LobbyChatMessageReportLockManager(redisTemplate);
    }

    @Test
    @DisplayName("Redis setIfAbsent가 true면 lock 획득에 성공한다")
    void tryLock_success() {
        // given
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                eq("1"),
                any(Duration.class)
        )).thenReturn(true);

        // when
        boolean result = lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Redis setIfAbsent가 false면 lock 획득에 실패한다")
    void tryLock_failsWhenLockAlreadyExists() {
        // given
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                eq("1"),
                any(Duration.class)
        )).thenReturn(false);

        // when
        boolean result = lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("unlock은 lock key를 삭제한다")
    void unlock_deletesLockKey() {
        // when
        lockManager.unlock(REPORTER_ID, LOBBY_ID, MESSAGE_ID);

        // then
        verify(redisTemplate).delete(LOCK_KEY);
    }
}