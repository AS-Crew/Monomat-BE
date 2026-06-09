package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LobbyRedisCommandRepository 단위 테스트.
 *
 * [테스트 범위]
 * - Redis 서버 연결 없이 StringRedisTemplate mock 기반으로 command 호출 계약만 검증한다.
 * - Lua 기반 상태 전이는 LobbyLuaScriptExecutor 계층에서 별도 검증한다.
 */
class LobbyRedisCommandRepositoryTest {

    private static final String CODE = "ABC123";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOperations =
            mock(HashOperations.class);

    private final LobbyRedisCommandRepository sut =
            new LobbyRedisCommandRepository(redisTemplate);

    @Test
    @DisplayName("updateSettings는 로비 Hash의 max_players, question_count, time_limit_seconds를 함께 갱신한다")
    void restoreSettings_putsSettingsFieldsIntoLobbyHash() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        sut.updateSettings(
                CODE,
                4,
                10,
                30
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);

        verify(hashOperations).putAll(
                eq(RedisKeys.lobbyKey(CODE)),
                payloadCaptor.capture()
        );

        Map<Object, Object> payload = payloadCaptor.getValue();

        assertThat(payload)
                .containsEntry(RedisKeys.FIELD_MAX_PLAYERS, "4")
                .containsEntry(RedisKeys.FIELD_QUESTION_COUNT, "10")
                .containsEntry(RedisKeys.FIELD_TIME_LIMIT_SECONDS, "30")
                .hasSize(3);
    }
}