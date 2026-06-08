package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LobbyLuaScriptExecutorTest {

    @Autowired
    private LobbyLuaScriptExecutor lobbyLuaScriptExecutor;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String lobbyCode;

    @AfterEach
    void tearDown() {
        if (lobbyCode == null) {
            return;
        }

        redisTemplate.delete(List.of(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                RedisKeys.lobbyOrderKey(lobbyCode),
                RedisKeys.lobbyKickedKey(lobbyCode),
                RedisKeys.lobbyReadyKey(lobbyCode),
                RedisKeys.lobbyUserSessionKey(lobbyCode, "user-a"),
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, "user-a"),
                RedisKeys.lobbyUserSessionKey(lobbyCode, "user-b"),
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, "user-b")
        ));

        redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForSet().remove(RedisKeys.LOBBY_ALL, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_LATEST, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, lobbyCode);

        lobbyCode = null;
    }

    @Test
    @DisplayName("퇴장 시 order 중복 전체, ready, user session 키를 정리한다")
    void executeLeaveLobby_removesAllDuplicatedOrderEntriesAndSessionKeys() {
        // given
        lobbyCode = newLobbyCode();
        String userA = "user-a";
        String userB = "user-b";

        redisTemplate.opsForHash().putAll(
                RedisKeys.lobbyKey(lobbyCode),
                java.util.Map.of(
                        RedisKeys.FIELD_CODE, lobbyCode,
                        RedisKeys.FIELD_HOST_USER_ID, userA,
                        RedisKeys.FIELD_CURRENT_PLAYERS, "2",
                        RedisKeys.FIELD_MAX_PLAYERS, "4",
                        RedisKeys.FIELD_IS_PRIVATE, "false",
                        "status", "WAITING"
                )
        );

        redisTemplate.opsForSet().add(
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                userA,
                userB
        );

        redisTemplate.opsForList().rightPushAll(
                RedisKeys.lobbyOrderKey(lobbyCode),
                userA,
                userA,
                userA,
                userB
        );

        redisTemplate.opsForSet().add(
                RedisKeys.lobbyReadyKey(lobbyCode),
                userA,
                userB
        );

        redisTemplate.opsForValue().set(
                RedisKeys.lobbyUserSessionKey(lobbyCode, userA),
                "ws-a"
        );

        redisTemplate.opsForValue().set(
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, userA),
                "1"
        );

        redisTemplate.opsForSet().add(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForSet().add(RedisKeys.LOBBY_ALL, lobbyCode);
        redisTemplate.opsForZSet().add(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, lobbyCode, 2);
        redisTemplate.opsForZSet().add(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, lobbyCode, 2);

        // when
        String result = lobbyLuaScriptExecutor.executeLeaveLobby(lobbyCode, userA);

        // then
        assertThat(result).isEqualTo("DELEGATED:" + userB);

        assertThat(redisTemplate.opsForSet().members(RedisKeys.lobbyParticipantsKey(lobbyCode)))
                .containsExactly(userB);

        assertThat(redisTemplate.opsForList().range(RedisKeys.lobbyOrderKey(lobbyCode), 0, -1))
                .containsExactly(userB);

        assertThat(redisTemplate.opsForSet().members(RedisKeys.lobbyReadyKey(lobbyCode)))
                .containsExactly(userB);

        assertThat(redisTemplate.opsForValue().get(RedisKeys.lobbyUserSessionKey(lobbyCode, userA)))
                .isNull();

        assertThat(redisTemplate.opsForValue().get(RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, userA)))
                .isNull();

        assertThat(redisTemplate.opsForHash().get(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.FIELD_CURRENT_PLAYERS
        )).isEqualTo("1");

        assertThat(redisTemplate.opsForHash().get(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.FIELD_HOST_USER_ID
        )).isEqualTo(userB);

        assertThat(redisTemplate.opsForZSet().score(
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                lobbyCode
        )).isEqualTo(1.0);

        assertThat(redisTemplate.opsForZSet().score(
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE,
                lobbyCode
        )).isEqualTo(3.0);
    }

    private String newLobbyCode() {
        return "TEST_LOBBY_" + UUID.randomUUID();
    }
}