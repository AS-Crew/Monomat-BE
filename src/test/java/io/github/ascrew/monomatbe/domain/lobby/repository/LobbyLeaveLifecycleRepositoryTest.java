package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false"
})
class LobbyLeaveLifecycleRepositoryTest {

    private static final String LOBBY_CODE = "TEST94";

    private static final String HOST_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND_USER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String THIRD_USER_ID = "33333333-3333-3333-3333-333333333333";

    @Autowired
    private LobbyRepository lobbyRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        deleteLobbyKeys(LOBBY_CODE);
    }

    @Test
    @DisplayName("방장이 정상 퇴장하면 입장 순서상 다음 유저에게 방장이 위임된다")
    void delegateHostToNextUserWhenHostLeaves() {
        // given
        givenLobby(
                LOBBY_CODE,
                HOST_ID,
                false,
                4,
                HOST_ID,
                SECOND_USER_ID,
                THIRD_USER_ID
        );

        // when
        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, HOST_ID);

        // then
        assertThat(result).isInstanceOf(LeaveLobbyResult.Delegated.class);

        LeaveLobbyResult.Delegated delegated = (LeaveLobbyResult.Delegated) result;
        assertThat(delegated.lobbyCode()).isEqualTo(LOBBY_CODE);
        assertThat(delegated.newHostId()).isEqualTo(SECOND_USER_ID);

        Object storedHost = redisTemplate.opsForHash()
                .get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_HOST_USER_ID);

        assertThat(storedHost).isEqualTo(SECOND_USER_ID);

        assertThat(redisTemplate.opsForSet().members(RedisKeys.lobbyParticipantsKey(LOBBY_CODE)))
                .containsExactlyInAnyOrder(SECOND_USER_ID, THIRD_USER_ID);

        assertThat(redisTemplate.opsForList().range(RedisKeys.lobbyOrderKey(LOBBY_CODE), 0, -1))
                .containsExactly(SECOND_USER_ID, THIRD_USER_ID);

        assertThat(redisTemplate.opsForHash()
                .get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_CURRENT_PLAYERS))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("일반 참가자가 퇴장하면 participants, order, ready set에서 제거된다")
    void removeParticipantStateWhenNormalUserLeaves() {
        // given
        givenLobby(
                LOBBY_CODE,
                HOST_ID,
                false,
                4,
                HOST_ID,
                SECOND_USER_ID,
                THIRD_USER_ID
        );

        redisTemplate.opsForSet().add(
                RedisKeys.lobbyReadyKey(LOBBY_CODE),
                SECOND_USER_ID,
                THIRD_USER_ID
        );

        // when
        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, SECOND_USER_ID);

        // then
        assertThat(result).isInstanceOf(LeaveLobbyResult.Left.class);

        LeaveLobbyResult.Left left = (LeaveLobbyResult.Left) result;
        assertThat(left.lobbyCode()).isEqualTo(LOBBY_CODE);
        assertThat(left.userId()).isEqualTo(SECOND_USER_ID);

        assertThat(redisTemplate.opsForSet().members(RedisKeys.lobbyParticipantsKey(LOBBY_CODE)))
                .containsExactlyInAnyOrder(HOST_ID, THIRD_USER_ID);

        assertThat(redisTemplate.opsForList().range(RedisKeys.lobbyOrderKey(LOBBY_CODE), 0, -1))
                .containsExactly(HOST_ID, THIRD_USER_ID);

        assertThat(redisTemplate.opsForSet().members(RedisKeys.lobbyReadyKey(LOBBY_CODE)))
                .containsExactly(THIRD_USER_ID);

        assertThat(redisTemplate.opsForHash()
                .get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_CURRENT_PLAYERS))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("마지막 유저가 퇴장하면 Redis 로비 데이터가 삭제된다")
    void destroyLobbyWhenLastUserLeaves() {
        // given
        givenLobby(
                LOBBY_CODE,
                HOST_ID,
                false,
                4,
                HOST_ID
        );

        redisTemplate.opsForSet().add(RedisKeys.lobbyReadyKey(LOBBY_CODE), HOST_ID);
        redisTemplate.opsForSet().add(RedisKeys.lobbyKickedKey(LOBBY_CODE), SECOND_USER_ID);
        addPublicIndexes(LOBBY_CODE, 1, 3);

        // when
        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, HOST_ID);

        // then
        assertThat(result).isInstanceOf(LeaveLobbyResult.Destroyed.class);

        LeaveLobbyResult.Destroyed destroyed = (LeaveLobbyResult.Destroyed) result;
        assertThat(destroyed.lobbyCode()).isEqualTo(LOBBY_CODE);

        assertThat(redisTemplate.hasKey(RedisKeys.lobbyKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyParticipantsKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyOrderKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyKickedKey(LOBBY_CODE))).isFalse();

        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_PUBLIC, LOBBY_CODE)).isFalse();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_LATEST, LOBBY_CODE)).isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, LOBBY_CODE)).isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, LOBBY_CODE)).isNull();
    }

    @Test
    @DisplayName("공개 로비에서 일반 참가자가 퇴장하면 public 인원 정렬 인덱스 score가 갱신된다")
    void updatePublicCapacityIndexesWhenPublicLobbyParticipantLeaves() {
        // given
        givenLobby(
                LOBBY_CODE,
                HOST_ID,
                false,
                4,
                HOST_ID,
                SECOND_USER_ID,
                THIRD_USER_ID
        );

        // when
        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, THIRD_USER_ID);

        // then
        assertThat(result).isInstanceOf(LeaveLobbyResult.Left.class);

        assertThat(redisTemplate.opsForHash()
                .get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_CURRENT_PLAYERS))
                .isEqualTo("2");

        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_PUBLIC, LOBBY_CODE))
                .isTrue();

        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, LOBBY_CODE))
                .isEqualTo(2.0);

        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, LOBBY_CODE))
                .isEqualTo(2.0);

        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_LATEST, LOBBY_CODE))
                .isNotNull();
    }

    @Test
    @DisplayName("비공개 로비에서 참가자가 퇴장해도 public index에 복구되지 않는다")
    void doesNotRestorePrivateLobbyToPublicIndexesWhenParticipantLeaves() {
        // given
        givenLobby(
                LOBBY_CODE,
                HOST_ID,
                true,
                4,
                HOST_ID,
                SECOND_USER_ID,
                THIRD_USER_ID
        );

        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_PUBLIC, LOBBY_CODE))
                .isFalse();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_LATEST, LOBBY_CODE))
                .isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, LOBBY_CODE))
                .isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, LOBBY_CODE))
                .isNull();

        // when
        LeaveLobbyResult result = lobbyRepository.executeLeaveLobbyProcess(LOBBY_CODE, SECOND_USER_ID);

        // then
        assertThat(result).isInstanceOf(LeaveLobbyResult.Left.class);

        assertThat(redisTemplate.opsForHash()
                .get(RedisKeys.lobbyKey(LOBBY_CODE), RedisKeys.FIELD_CURRENT_PLAYERS))
                .isEqualTo("2");

        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_PUBLIC, LOBBY_CODE))
                .isFalse();

        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_LATEST, LOBBY_CODE))
                .isNull();

        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, LOBBY_CODE))
                .isNull();

        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, LOBBY_CODE))
                .isNull();
    }

    private void givenLobby(
            String lobbyCode,
            String hostUserId,
            boolean isPrivate,
            int maxPlayers,
            String... participantIds
    ) {
        deleteLobbyKeys(lobbyCode);

        redisTemplate.opsForHash().putAll(
                RedisKeys.lobbyKey(lobbyCode),
                Map.of(
                        RedisKeys.FIELD_CODE, lobbyCode,
                        RedisKeys.FIELD_HOST_USER_ID, hostUserId,
                        RedisKeys.FIELD_TITLE, "테스트 로비",
                        RedisKeys.FIELD_MAX_PLAYERS, String.valueOf(maxPlayers),
                        RedisKeys.FIELD_CURRENT_PLAYERS, String.valueOf(participantIds.length),
                        RedisKeys.FIELD_IS_PRIVATE, String.valueOf(isPrivate),
                        RedisKeys.FIELD_STATUS, "WAITING",
                        RedisKeys.FIELD_CREATED_AT_EPOCH_MILLIS, String.valueOf(System.currentTimeMillis())
                )
        );

        for (String participantId : participantIds) {
            redisTemplate.opsForSet().add(RedisKeys.lobbyParticipantsKey(lobbyCode), participantId);
            redisTemplate.opsForList().rightPush(RedisKeys.lobbyOrderKey(lobbyCode), participantId);
        }

        if (!isPrivate) {
            int currentPlayers = participantIds.length;
            int availableSeats = Math.max(0, maxPlayers - currentPlayers);
            addPublicIndexes(lobbyCode, currentPlayers, availableSeats);
        }
    }

    private void addPublicIndexes(String lobbyCode, int currentPlayers, int availableSeats) {
        redisTemplate.opsForSet().add(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForZSet().add(
                RedisKeys.LOBBY_PUBLIC_LATEST,
                lobbyCode,
                System.currentTimeMillis()
        );
        redisTemplate.opsForZSet().add(
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                lobbyCode,
                currentPlayers
        );
        redisTemplate.opsForZSet().add(
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE,
                lobbyCode,
                availableSeats
        );
    }

    private void deleteLobbyKeys(String lobbyCode) {
        redisTemplate.delete(Set.of(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                RedisKeys.lobbyOrderKey(lobbyCode),
                RedisKeys.lobbyKickedKey(lobbyCode),
                RedisKeys.lobbyReadyKey(lobbyCode),
                RedisKeys.lobbyUserSessionKey(lobbyCode, HOST_ID),
                RedisKeys.lobbyUserSessionKey(lobbyCode, SECOND_USER_ID),
                RedisKeys.lobbyUserSessionKey(lobbyCode, THIRD_USER_ID),
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, HOST_ID),
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, SECOND_USER_ID),
                RedisKeys.lobbyUserSessionSequenceKey(lobbyCode, THIRD_USER_ID)
        ));

        redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_LATEST, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, lobbyCode);
    }
}