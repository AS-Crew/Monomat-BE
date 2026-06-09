package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyRedisCommandRepositoryTest {

    private static final String CODE = "ABC123";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOperations = mock(SetOperations.class);

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

    private final LobbyRedisCommandRepository sut =
            new LobbyRedisCommandRepository(redisTemplate);

    @Test
    @DisplayName("Redis 로비 보상 삭제 시 로비 키, 공개 Set, 전체 Set, 공개 정렬 ZSET 인덱스를 모두 제거한다")
    void deleteFromRedis_deletesLobbyKeysAndAllPublicIndexes() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        boolean result = sut.deleteFromRedis(CODE);

        assertThat(result).isTrue();

        verify(redisTemplate).delete(List.of(
                RedisKeys.lobbyKey(CODE),
                RedisKeys.lobbyParticipantsKey(CODE),
                RedisKeys.lobbyOrderKey(CODE),
                RedisKeys.lobbyKickedKey(CODE),
                RedisKeys.lobbyReadyKey(CODE),
                RedisKeys.lobbyCodeLockKey(CODE)
        ));

        verify(setOperations).remove(RedisKeys.LOBBY_PUBLIC, CODE);
        verify(setOperations).remove(RedisKeys.LOBBY_ALL, CODE);

        verify(zSetOperations).remove(RedisKeys.LOBBY_PUBLIC_LATEST, CODE);
        verify(zSetOperations).remove(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, CODE);
        verify(zSetOperations).remove(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, CODE);
    }

    @Test
    @DisplayName("Redis 로비 보상 삭제 중 예외가 발생하면 false를 반환한다")
    void deleteFromRedis_returnsFalse_whenRedisDeleteFails() {
        doThrow(new RuntimeException("Redis 장애"))
                .when(redisTemplate)
                .delete(anyCollection());

        boolean result = sut.deleteFromRedis(CODE);

        assertThat(result).isFalse();
    }
}