package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LobbyRedisQueryRepositoryTest {

    @Autowired
    private LobbyRedisQueryRepository lobbyRedisQueryRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> usedLobbyCodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String lobbyCode : usedLobbyCodes) {
            redisTemplate.delete(List.of(
                    RedisKeys.lobbyParticipantsKey(lobbyCode),
                    RedisKeys.lobbyOrderKey(lobbyCode)
            ));
            redisTemplate.opsForSet().remove(RedisKeys.LOBBY_ALL, lobbyCode);
        }

        redisTemplate.delete(List.of(
                RedisKeys.LOBBY_ALL_REAPER_SCAN_CURSOR,
                RedisKeys.LOBBY_ALL_REAPER_SCAN_BUFFER
        ));

        usedLobbyCodes.clear();
    }

    @Test
    @DisplayName("입장 순서 List에 같은 사용자가 중복되어 있어도 참여자 목록은 중복 없이 반환한다")
    void getParticipantIdentifiers_removesDuplicatedOrderEntries() {
        // given
        String lobbyCode = newLobbyCode();
        String userA = newUserIdentifier("user-a");
        String userB = newUserIdentifier("user-b");

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
                userB,
                userA
        );

        // when
        List<String> result = lobbyRedisQueryRepository.getParticipantIdentifiers(lobbyCode);

        // then
        assertThat(result).containsExactly(userA, userB);
    }

    @Test
    @DisplayName("order에는 없지만 participants Set에만 있는 사용자는 누락 방지를 위해 뒤에 보정한다")
    void getParticipantIdentifiers_appendsParticipantsMissingFromOrder() {
        // given
        String lobbyCode = newLobbyCode();
        String userA = newUserIdentifier("user-a");
        String userB = newUserIdentifier("user-b");

        redisTemplate.opsForSet().add(
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                userA,
                userB
        );

        redisTemplate.opsForList().rightPush(
                RedisKeys.lobbyOrderKey(lobbyCode),
                userA
        );

        // when
        List<String> result = lobbyRedisQueryRepository.getParticipantIdentifiers(lobbyCode);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.getFirst()).isEqualTo(userA);
        assertThat(result).contains(userB);
    }

    @Test
    @DisplayName("order에 남아 있지만 participants Set에 없는 사용자는 반환하지 않는다")
    void getParticipantIdentifiers_excludesStaleOrderEntries() {
        // given
        String lobbyCode = newLobbyCode();
        String activeUser = newUserIdentifier("active-user");
        String staleUser = newUserIdentifier("stale-user");

        redisTemplate.opsForSet().add(
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                activeUser
        );

        redisTemplate.opsForList().rightPushAll(
                RedisKeys.lobbyOrderKey(lobbyCode),
                staleUser,
                activeUser
        );

        // when
        List<String> result = lobbyRedisQueryRepository.getParticipantIdentifiers(lobbyCode);

        // then
        assertThat(result).containsExactly(activeUser);
    }

    @Test
    @DisplayName("participants Set이 비어 있으면 order에 값이 남아 있어도 빈 목록을 반환한다")
    void getParticipantIdentifiers_returnsEmptyListWhenParticipantsSetIsEmpty() {
        // given
        String lobbyCode = newLobbyCode();
        String staleUser = newUserIdentifier("stale-user");

        redisTemplate.opsForList().rightPush(
                RedisKeys.lobbyOrderKey(lobbyCode),
                staleUser
        );

        // when
        List<String> result = lobbyRedisQueryRepository.getParticipantIdentifiers(lobbyCode);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("reaper 후보 조회는 저장된 SSCAN cursor와 buffer를 사용해 lobby:all 전체를 점진 순회한다")
    void getAllLobbyCodesForReaping_continuesScanWithStoredCursor() {
        // given
        Set<String> expectedCodes = new LinkedHashSet<>();
        for (int i = 0; i < 30; i++) {
            String lobbyCode = newLobbyCode();
            expectedCodes.add(lobbyCode);
            redisTemplate.opsForSet().add(RedisKeys.LOBBY_ALL, lobbyCode);
        }

        Set<String> actualCodes = new LinkedHashSet<>();

        // when
        for (int i = 0; i < 30 && !actualCodes.containsAll(expectedCodes); i++) {
            List<String> candidates = lobbyRedisQueryRepository.getAllLobbyCodesForReaping(3);

            assertThat(candidates).hasSizeLessThanOrEqualTo(3);
            actualCodes.addAll(candidates);
        }

        // then
        assertThat(actualCodes).containsAll(expectedCodes);
    }

    private String newLobbyCode() {
        String lobbyCode = "TEST_LOBBY_" + UUID.randomUUID();
        usedLobbyCodes.add(lobbyCode);
        return lobbyCode;
    }

    private String newUserIdentifier(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
