package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.service.UserNicknameLookupService;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LobbyPlayerNicknameResolver의 로비 응답용 닉네임 변환 정책을 검증한다.
 *
 * [검증 범위]
 * - Auth 도메인 닉네임 조회 서비스로 위임하는지
 * - fallback nickname 생성 정책
 */
class LobbyPlayerNicknameResolverTest {

    private final UserNicknameLookupService userNicknameLookupService = mock(UserNicknameLookupService.class);

    private final LobbyPlayerNicknameResolver resolver = new LobbyPlayerNicknameResolver(
            userNicknameLookupService
    );

    @Test
    @DisplayName("resolveNicknameMap은 UserNicknameLookupService에 조회를 위임한다")
    void resolveNicknameMap_delegatesToUserNicknameLookupService() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

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