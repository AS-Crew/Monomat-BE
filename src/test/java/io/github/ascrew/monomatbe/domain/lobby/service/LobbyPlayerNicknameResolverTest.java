package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LobbyPlayerNicknameResolver의 로비 응답용 닉네임 변환 정책을 검증한다.
 *
 * [검증 범위]
 * - Cache -> DB(miss only) 계층 조회
 * - 캐시 적중분은 DB 조회 대상에서 제외
 * - 캐시 조회 실패 시 DB fallback
 * - DB 조회 결과 캐싱
 * - fallback nickname 생성 정책
 */
class LobbyPlayerNicknameResolverTest {

    private final UserNicknameLookupService userNicknameLookupService = mock(UserNicknameLookupService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final LobbyPlayerNicknameResolver resolver = new LobbyPlayerNicknameResolver(
            redisTemplate,
            userNicknameLookupService
    );

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("전부 캐시 미스면 DB로 위임 조회한 뒤 결과를 캐싱한다")
    void resolveNicknameMap_allCacheMiss_delegatesToDbAndCaches() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(valueOperations.multiGet(any())).thenReturn(Arrays.asList(null, null));
        when(userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers))
                .thenReturn(Map.of(
                        "guest-identifier", "게스트닉네임",
                        "registered-identifier", "회원닉네임"
                ));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", "게스트닉네임")
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(2);

        verify(userNicknameLookupService).findNicknameMapByUserIdentifiers(userIdentifiers);
        verify(valueOperations).set(
                eq(RedisKeys.userNicknameKey("guest-identifier")),
                eq("게스트닉네임"),
                any(Duration.class)
        );
        verify(valueOperations).set(
                eq(RedisKeys.userNicknameKey("registered-identifier")),
                eq("회원닉네임"),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("캐시 적중분은 DB 조회 대상에서 제외하고 미스인 식별자만 위임한다")
    void resolveNicknameMap_partialHit_onlyMissDelegated() {
        // given
        List<String> userIdentifiers = List.of(
                "cached-identifier",
                "missed-identifier"
        );

        // multiGet은 keys 순서와 정렬된 결과를 반환한다.
        when(valueOperations.multiGet(any())).thenReturn(Arrays.asList("캐시닉네임", null));
        when(userNicknameLookupService.findNicknameMapByUserIdentifiers(List.of("missed-identifier")))
                .thenReturn(Map.of("missed-identifier", "DB닉네임"));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("cached-identifier", "캐시닉네임")
                .containsEntry("missed-identifier", "DB닉네임")
                .hasSize(2);

        verify(userNicknameLookupService).findNicknameMapByUserIdentifiers(List.of("missed-identifier"));
        // 적중분은 다시 캐싱하지 않는다.
        verify(valueOperations, never()).set(
                eq(RedisKeys.userNicknameKey("cached-identifier")),
                anyString(),
                any(Duration.class)
        );
    }

    @Test
    @DisplayName("전부 캐시 적중이면 DB 조회를 호출하지 않는다")
    void resolveNicknameMap_allHit_skipsDb() {
        // given
        List<String> userIdentifiers = List.of("a", "b");
        when(valueOperations.multiGet(any())).thenReturn(Arrays.asList("닉a", "닉b"));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("a", "닉a")
                .containsEntry("b", "닉b")
                .hasSize(2);

        verify(userNicknameLookupService, never()).findNicknameMapByUserIdentifiers(any());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("캐시 조회가 예외를 던지면 전부 DB fallback으로 조회한다")
    void resolveNicknameMap_cacheFailure_fallsBackToDb() {
        // given
        List<String> userIdentifiers = List.of("guest-identifier", "registered-identifier");

        when(valueOperations.multiGet(any())).thenThrow(new RuntimeException("redis down"));
        when(userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers))
                .thenReturn(Map.of(
                        "guest-identifier", "게스트닉네임",
                        "registered-identifier", "회원닉네임"
                ));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", "게스트닉네임")
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(2);

        verify(userNicknameLookupService).findNicknameMapByUserIdentifiers(userIdentifiers);
    }

    @Test
    @DisplayName("blank/null 식별자만 있으면 빈 맵을 반환하고 캐시/DB를 조회하지 않는다")
    void resolveNicknameMap_blankIdentifiers_returnsEmpty() {
        // when
        Map<String, String> result = resolver.resolveNicknameMap(Arrays.asList(null, "", "   "));

        // then
        assertThat(result).isEmpty();
        verify(valueOperations, never()).multiGet(any());
        verify(userNicknameLookupService, never()).findNicknameMapByUserIdentifiers(any());
    }

    @Test
    @DisplayName("fallbackNickname은 null 또는 blank 식별자에 Unknown-user를 반환한다")
    void fallbackNickname_returnsUnknownUserWhenIdentifierIsBlank() {
        assertThat(resolver.fallbackNickname(null)).isEqualTo("Unknown-user");
        assertThat(resolver.fallbackNickname("")).isEqualTo("Unknown-user");
        assertThat(resolver.fallbackNickname("   ")).isEqualTo("Unknown-user");
    }

    @Test
    @DisplayName("fallbackNickname은 식별자의 SHA-256 해시 앞 6자리를 suffix로 사용한다")
    void fallbackNickname_usesHashedSuffix() {
        String userIdentifier = "11111111-2222-3333-4444-555555555555";

        String result = resolver.fallbackNickname(userIdentifier);

        String expectedSuffix = TokenHashUtils.sha256(userIdentifier).substring(0, 6);
        assertThat(result).isEqualTo("Unknown-" + expectedSuffix);
    }

    @Test
    @DisplayName("fallbackNickname은 식별자 원문 fragment를 노출하지 않고 비가역 해시 포맷을 사용한다")
    void fallbackNickname_doesNotExposeRawIdentifier() {
        String userIdentifier = "11111111-2222-3333-4444-555555555555";

        String result = resolver.fallbackNickname(userIdentifier);

        // 식별자 원문(하이픈 제거 앞자리 포함)을 그대로 포함하면 안 된다.
        assertThat(result)
                .doesNotContain("111111")
                .matches("Unknown-[0-9a-f]{6}");

        // 결정적: 같은 식별자는 항상 같은 fallback 값으로 표시된다.
        assertThat(resolver.fallbackNickname(userIdentifier)).isEqualTo(result);
    }
}
