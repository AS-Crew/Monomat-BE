package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.ReapLobbyResult;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빈 로비 reaper(reap_lobby.lua + reapEmptyLobby) 통합 테스트.
 *
 * 이슈 #167: 비정상 종료/생성 직후 미구독으로 발생하는 유령/빈 로비가
 * grace 경과 후 활성 세션 0이면 폭파되는지, 온라인 참여자가 있거나 grace 미경과면
 * 보존되는지를 실제 Redis에 대해 검증한다.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none"
})
class LobbyReaperLifecycleRepositoryTest {

    private static final String LOBBY_CODE = "REAP94";
    private static final long GRACE_MS = 60_000L;

    private static final String HOST_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND_USER_ID = "22222222-2222-2222-2222-222222222222";

    @Autowired
    private LobbyRepository lobbyRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        deleteLobbyKeys(LOBBY_CODE);
    }

    @Test
    @DisplayName("생성 직후 참여자가 0명이고 grace가 지났으면 빈 로비를 폭파한다 (버그①)")
    void reapsEmptyLobbyWithNoParticipantsAfterGrace() {
        // given: 참여자 0명, 10분 전 생성된 공개 로비
        givenLobby(LOBBY_CODE, HOST_ID, false, 4, agedCreatedAt());
        addPublicIndexes(LOBBY_CODE, 0, 4);

        // when
        ReapLobbyResult result = lobbyRepository.reapEmptyLobby(LOBBY_CODE, GRACE_MS);

        // then
        assertThat(result).isEqualTo(ReapLobbyResult.REAPED);
        assertLobbyFullyRemoved();
    }

    @Test
    @DisplayName("참여자가 있어도 전원 오프라인(user_status 없음)이고 grace가 지났으면 폭파한다 (버그②)")
    void reapsLobbyWhenAllParticipantsOffline() {
        // given: 참여자는 있으나 user_status 키가 없는(=오프라인) 10분 전 생성 로비
        givenLobby(LOBBY_CODE, HOST_ID, false, 4, agedCreatedAt(), HOST_ID, SECOND_USER_ID);
        addPublicIndexes(LOBBY_CODE, 2, 2);

        // when
        ReapLobbyResult result = lobbyRepository.reapEmptyLobby(LOBBY_CODE, GRACE_MS);

        // then
        assertThat(result).isEqualTo(ReapLobbyResult.REAPED);
        assertLobbyFullyRemoved();
    }

    @Test
    @DisplayName("참여자 중 한 명이라도 온라인(user_status 존재)이면 보존한다")
    void keepsLobbyWhenAnyParticipantOnline() {
        // given: 참여자 2명 중 1명 온라인
        givenLobby(LOBBY_CODE, HOST_ID, false, 4, agedCreatedAt(), HOST_ID, SECOND_USER_ID);
        redisTemplate.opsForValue().set(RedisKeys.userStatusKey(SECOND_USER_ID), "ONLINE");

        // when
        ReapLobbyResult result = lobbyRepository.reapEmptyLobby(LOBBY_CODE, GRACE_MS);

        // then
        assertThat(result).isEqualTo(ReapLobbyResult.ALIVE);
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyKey(LOBBY_CODE))).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_ALL, LOBBY_CODE)).isTrue();

        redisTemplate.delete(RedisKeys.userStatusKey(SECOND_USER_ID));
    }

    @Test
    @DisplayName("생성 후 grace가 지나지 않은 로비는 0명이라도 보존한다 (구독 대기 보호)")
    void keepsYoungLobbyWithinGrace() {
        // given: 방금 생성된(=grace 이내) 참여자 0명 로비
        givenLobby(LOBBY_CODE, HOST_ID, false, 4, System.currentTimeMillis());

        // when
        ReapLobbyResult result = lobbyRepository.reapEmptyLobby(LOBBY_CODE, GRACE_MS);

        // then
        assertThat(result).isEqualTo(ReapLobbyResult.TOO_YOUNG);
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyKey(LOBBY_CODE))).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_ALL, LOBBY_CODE)).isTrue();
    }

    @Test
    @DisplayName("lobby:all에 코드만 있고 Hash가 없으면 stale 인덱스를 self-heal 한다")
    void selfHealsStaleAllIndexEntry() {
        // given: Hash 없이 lobby:all 및 공개 인덱스에만 잔존하는 stale 코드
        deleteLobbyKeys(LOBBY_CODE);
        redisTemplate.opsForSet().add(RedisKeys.LOBBY_ALL, LOBBY_CODE);
        addPublicIndexes(LOBBY_CODE, 0, 4);

        // when
        ReapLobbyResult result = lobbyRepository.reapEmptyLobby(LOBBY_CODE, GRACE_MS);

        // then
        assertThat(result).isEqualTo(ReapLobbyResult.STALE_INDEX);
        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_ALL, LOBBY_CODE)).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_PUBLIC, LOBBY_CODE)).isFalse();
    }

    private long agedCreatedAt() {
        return System.currentTimeMillis() - (10 * 60_000L);
    }

    private void assertLobbyFullyRemoved() {
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyParticipantsKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyOrderKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyKickedKey(LOBBY_CODE))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.lobbyReadyKey(LOBBY_CODE))).isFalse();

        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_ALL, LOBBY_CODE)).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(RedisKeys.LOBBY_PUBLIC, LOBBY_CODE)).isFalse();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_LATEST, LOBBY_CODE)).isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, LOBBY_CODE)).isNull();
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, LOBBY_CODE)).isNull();
    }

    private void givenLobby(
            String lobbyCode,
            String hostUserId,
            boolean isPrivate,
            int maxPlayers,
            long createdAtEpochMillis,
            String... participantIds
    ) {
        deleteLobbyKeys(lobbyCode);

        Map<String, String> hash = new HashMap<>();
        hash.put(RedisKeys.FIELD_CODE, lobbyCode);
        hash.put(RedisKeys.FIELD_HOST_USER_ID, hostUserId);
        hash.put(RedisKeys.FIELD_TITLE, "테스트 로비");
        hash.put(RedisKeys.FIELD_MAX_PLAYERS, String.valueOf(maxPlayers));
        hash.put(RedisKeys.FIELD_CURRENT_PLAYERS, String.valueOf(participantIds.length));
        hash.put(RedisKeys.FIELD_IS_PRIVATE, String.valueOf(isPrivate));
        hash.put(RedisKeys.FIELD_STATUS, "WAITING");
        hash.put(RedisKeys.FIELD_CREATED_AT_EPOCH_MILLIS, String.valueOf(createdAtEpochMillis));

        redisTemplate.opsForHash().putAll(RedisKeys.lobbyKey(lobbyCode), hash);

        for (String participantId : participantIds) {
            redisTemplate.opsForSet().add(RedisKeys.lobbyParticipantsKey(lobbyCode), participantId);
            redisTemplate.opsForList().rightPush(RedisKeys.lobbyOrderKey(lobbyCode), participantId);
        }

        redisTemplate.opsForSet().add(RedisKeys.LOBBY_ALL, lobbyCode);
    }

    private void addPublicIndexes(String lobbyCode, int currentPlayers, int availableSeats) {
        redisTemplate.opsForSet().add(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForZSet().add(RedisKeys.LOBBY_PUBLIC_LATEST, lobbyCode, System.currentTimeMillis());
        redisTemplate.opsForZSet().add(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, lobbyCode, currentPlayers);
        redisTemplate.opsForZSet().add(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, lobbyCode, availableSeats);
    }

    private void deleteLobbyKeys(String lobbyCode) {
        redisTemplate.delete(Set.of(
                RedisKeys.lobbyKey(lobbyCode),
                RedisKeys.lobbyParticipantsKey(lobbyCode),
                RedisKeys.lobbyOrderKey(lobbyCode),
                RedisKeys.lobbyKickedKey(lobbyCode),
                RedisKeys.lobbyReadyKey(lobbyCode)
        ));

        redisTemplate.delete(RedisKeys.userStatusKey(HOST_ID));
        redisTemplate.delete(RedisKeys.userStatusKey(SECOND_USER_ID));

        redisTemplate.opsForSet().remove(RedisKeys.LOBBY_ALL, lobbyCode);
        redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_LATEST, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS, lobbyCode);
        redisTemplate.opsForZSet().remove(RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE, lobbyCode);
    }
}
