package io.github.ascrew.monomatbe.global.constant;

import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisKeys 키 생성 정책 검증.
 *
 * 특히 userNicknameKey는 세션/게스트 토큰 성격의 식별자 원문을 키에 노출하지 않아야 한다.
 */
class RedisKeysTest {

    @Test
    @DisplayName("userNicknameKey는 식별자 원문 대신 SHA-256 해시를 사용한다")
    void userNicknameKey_usesSha256Hash() {
        String userIdentifier = "11111111-2222-3333-4444-555555555555";

        String key = RedisKeys.userNicknameKey(userIdentifier);

        assertThat(key).isEqualTo("user:nickname:" + TokenHashUtils.sha256(userIdentifier));
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
}
