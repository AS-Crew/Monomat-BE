package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("Redis setIfAbsent가 true면 lock 정보를 반환한다")
    void tryLock_success() {
        // given
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                any(String.class),
                any(Duration.class)
        )).thenReturn(true);

        // when
        Optional<LobbyChatMessageReportLock> result = lockManager.tryLock(
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID
        );

        // then
        assertThat(result).isPresent();

        LobbyChatMessageReportLock lock = result.get();

        assertThat(lock.key()).isEqualTo(LOCK_KEY);
        assertThat(lock.token()).isNotBlank();
        assertThat(lock.reporterId()).isEqualTo(REPORTER_ID);
        assertThat(lock.lobbyId()).isEqualTo(LOBBY_ID);
        assertThat(lock.messageId()).isEqualTo(MESSAGE_ID);
    }

    @Test
    @DisplayName("Redis setIfAbsent가 false면 Optional.empty를 반환한다")
    void tryLock_failsWhenLockAlreadyExists() {
        // given
        when(valueOperations.setIfAbsent(
                eq(LOCK_KEY),
                any(String.class),
                any(Duration.class)
        )).thenReturn(false);

        // when
        Optional<LobbyChatMessageReportLock> result = lockManager.tryLock(
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID
        );

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("unlock은 Lua compare-and-delete 스크립트를 실행한다")
    void unlock_executesCompareAndDeleteScript() {
        // given
        LobbyChatMessageReportLock lock = new LobbyChatMessageReportLock(
                LOCK_KEY,
                "lock-token",
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID
        );

        // when
        lockManager.unlock(lock);

        // then
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(LOCK_KEY)),
                eq("lock-token")
        );
    }
}