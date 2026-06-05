package io.github.ascrew.monomatbe.global.constant;

import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisKeys 키 생성 정책 검증.
 *
 * 특히 userNicknameKey는 세션/게스트 토큰 성격의 식별자 원문을 키에 노출하지 않아야 한다.
 * Redis Cluster 대비 hash-tag({lobbyCode})가 game:session 패밀리 전 키에 일관되게 적용되어
 * 동일 게임 세션 키들이 같은 슬롯에 모이는지(= 같은 hash-tag를 공유하는지) 고정한다.
 */
class RedisKeysTest {

    private static final String CODE = "ABC123";

    @Test
    @DisplayName("게임 세션 base 키는 {lobbyCode} hash-tag를 포함한다")
    void gameSessionKey_containsHashTag() {
        assertThat(RedisKeys.gameSessionKey(CODE)).isEqualTo("game:session:{ABC123}");
    }

    @Test
    @DisplayName("userNicknameKey는 식별자 원문 대신 SHA-256 해시를 사용한다")
    void userNicknameKey_usesSha256Hash() {
        String userIdentifier = "11111111-2222-3333-4444-555555555555";

        String key = RedisKeys.userNicknameKey(userIdentifier);

        assertThat(key).isEqualTo("user:nickname:" + TokenHashUtils.sha256(userIdentifier));
    }

    @Test
    @DisplayName("game:session 패밀리의 모든 키가 동일한 {lobbyCode} hash-tag를 공유한다")
    void allGameSessionKeys_shareSameHashTag() {
        String tag = "{" + CODE + "}";

        assertThat(RedisKeys.gameSessionKey(CODE)).contains(tag);
        assertThat(RedisKeys.gameSessionRoundsKey(CODE)).contains(tag).endsWith(":rounds");
        assertThat(RedisKeys.gameSessionPlayersKey(CODE)).contains(tag).endsWith(":players");
        assertThat(RedisKeys.gameSessionRoundReadyKey(CODE, 2)).contains(tag).endsWith(":round:2:ready");
        assertThat(RedisKeys.gameSessionPlaybackLockKey(CODE, 2)).contains(tag).endsWith(":round:2:playback_lock");
        assertThat(RedisKeys.gameSessionRoundDataKey(CODE, 2)).contains(tag).endsWith(":round:2:data");
        assertThat(RedisKeys.gameSessionRoundCorrectPlayersKey(CODE, 2)).contains(tag).endsWith(":round:2:correct_players");
        assertThat(RedisKeys.gameSessionRoundCorrectTimesKey(CODE, 2)).contains(tag).endsWith(":round:2:correct_times");
        assertThat(RedisKeys.gameSessionRoundEndedLockKey(CODE, 2)).contains(tag).endsWith(":round:2:ended_lock");
        assertThat(RedisKeys.gameSessionRoundNextLockKey(CODE, 2)).contains(tag).endsWith(":round:2:next_round_lock");
    }

    @Test
    @DisplayName("하위 키는 base 키를 접두로 하여 hash-tag를 상속한다 (Lua concat 조립 호환)")
    void derivedKeys_arePrefixedByBaseKey() {
        String base = RedisKeys.gameSessionKey(CODE);

        assertThat(RedisKeys.gameSessionRoundsKey(CODE)).startsWith(base + ":");
        assertThat(RedisKeys.gameSessionRoundReadyKey(CODE, 1)).startsWith(base + ":round:1:");
    }

    @Test
    @DisplayName("userNicknameKey는 식별자 원문 fragment를 키에 노출하지 않는다")
    void userNicknameKey_doesNotExposeRawIdentifier() {
        String userIdentifier = "guest-token-abcdef123456";

        String key = RedisKeys.userNicknameKey(userIdentifier);

        assertThat(key)
                .startsWith("user:nickname:")
                .doesNotContain(userIdentifier)
                .matches("user:nickname:[0-9a-f]{64}");
    }

    @Test
    @DisplayName("공개 맵 목록 기본 캐시 key는 응답 schema version을 포함한다")
    void mapPublicListKey_includesSchemaVersion() {
        String key = RedisKeys.mapPublicListKey("1", 0, 20);

        assertThat(key).isEqualTo("map:public:list:schema:2:v:1:p:0:s:20");
    }

    @Test
    @DisplayName("공개 맵 목록 조건 캐시 key는 응답 schema version을 포함한다")
    void mapPublicListKeyWithCondition_includesSchemaVersion() {
        String key = RedisKeys.mapPublicListKey(
                "1",
                null,
                "KPOP",
                "NEWEST",
                0,
                20
        );

        assertThat(key).isEqualTo("map:public:list:schema:2:v:1:k::c:KPOP:sort:NEWEST:p:0:s:20");
    }

    @Test
    @DisplayName("공개 맵 상세 캐시 key는 v2 prefix를 사용한다")
    void mapPublicDetailKey_usesVersionedPrefix() {
        String key = RedisKeys.mapPublicDetailKey(300L);

        assertThat(key).isEqualTo("map:public:v2:300");
    }
}