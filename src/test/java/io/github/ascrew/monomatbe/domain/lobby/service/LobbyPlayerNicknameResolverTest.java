package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserIdentifierNicknameProjection;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LobbyPlayerNicknameResolver의 userIdentifier -> nickname 변환 정책을 검증한다.
 *
 * [검증 범위]
 * - 빈 입력 처리
 * - 게스트 닉네임 Projection 반영
 * - 회원 닉네임 Projection 반영
 * - 일부 조회 실패 시 예외 전파 방지
 * - fallback nickname 생성 정책
 */
class LobbyPlayerNicknameResolverTest {

    private final GuestSessionRepository guestSessionRepository = mock(GuestSessionRepository.class);
    private final UserSessionRepository userSessionRepository = mock(UserSessionRepository.class);

    private final LobbyPlayerNicknameResolver resolver = new LobbyPlayerNicknameResolver(
            guestSessionRepository,
            userSessionRepository
    );

    @Test
    @DisplayName("userIdentifiers가 null이면 빈 Map을 반환하고 Repository를 호출하지 않는다")
    void resolveNicknameMap_returnsEmptyMapWhenUserIdentifiersIsNull() {
        // when
        Map<String, String> result = resolver.resolveNicknameMap(null);

        // then
        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).findNicknamesByGuestTokenIn(List.of());
        verify(userSessionRepository, never()).findNicknamesBySessionIdIn(List.of());
    }

    @Test
    @DisplayName("userIdentifiers가 비어 있으면 빈 Map을 반환하고 Repository를 호출하지 않는다")
    void resolveNicknameMap_returnsEmptyMapWhenUserIdentifiersIsEmpty() {
        // when
        Map<String, String> result = resolver.resolveNicknameMap(List.of());

        // then
        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).findNicknamesByGuestTokenIn(List.of());
        verify(userSessionRepository, never()).findNicknamesBySessionIdIn(List.of());
    }

    @Test
    @DisplayName("게스트와 회원 닉네임 Projection을 userIdentifier 기준 Map으로 변환한다")
    void resolveNicknameMap_resolvesGuestAndRegisteredUserNicknames() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findNicknamesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(projection("guest-identifier", "게스트닉네임")));

        when(userSessionRepository.findNicknamesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of(projection("registered-identifier", "회원닉네임")));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", "게스트닉네임")
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(2);
    }

    @Test
    @DisplayName("게스트 닉네임 조회가 실패해도 회원 닉네임 조회 결과는 반환한다")
    void resolveNicknameMap_continuesWhenGuestLookupFails() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findNicknamesByGuestTokenIn(userIdentifiers))
                .thenThrow(new RuntimeException("guest lookup failed"));

        when(userSessionRepository.findNicknamesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of(projection("registered-identifier", "회원닉네임")));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("회원 닉네임 조회가 실패해도 게스트 닉네임 조회 결과는 반환한다")
    void resolveNicknameMap_continuesWhenRegisteredUserLookupFails() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findNicknamesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(projection("guest-identifier", "게스트닉네임")));

        when(userSessionRepository.findNicknamesBySessionIdIn(userIdentifiers))
                .thenThrow(new RuntimeException("registered lookup failed"));

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", "게스트닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("빈 userIdentifier 또는 빈 nickname Projection은 결과에서 제외한다")
    void resolveNicknameMap_ignoresInvalidProjectionValues() {
        // given
        List<String> userIdentifiers = List.of(
                "valid-identifier",
                "blank-nickname",
                "blank-identifier"
        );

        when(guestSessionRepository.findNicknamesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(
                        projection("valid-identifier", "정상닉네임"),
                        projection("blank-nickname", " "),
                        projection(" ", "식별자없음")
                ));

        when(userSessionRepository.findNicknamesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of());

        // when
        Map<String, String> result = resolver.resolveNicknameMap(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("valid-identifier", "정상닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("fallbackNickname은 null 또는 blank 식별자에 Unknown-user를 반환한다")
    void fallbackNickname_returnsUnknownUserWhenIdentifierIsBlank() {
        assertThat(resolver.fallbackNickname(null)).isEqualTo("Unknown-user");
        assertThat(resolver.fallbackNickname("")).isEqualTo("Unknown-user");
        assertThat(resolver.fallbackNickname("   ")).isEqualTo("Unknown-user");
    }

    @Test
    @DisplayName("fallbackNickname은 식별자의 하이픈을 제거한 앞 6자리를 suffix로 사용한다")
    void fallbackNickname_returnsPrefixWithIdentifierSuffix() {
        String userIdentifier = "11111111-2222-3333-4444-555555555555";

        String result = resolver.fallbackNickname(userIdentifier);

        assertThat(result).isEqualTo("Unknown-111111");
    }

    private UserIdentifierNicknameProjection projection(
            String userIdentifier,
            String nickname
    ) {
        return new TestUserIdentifierNicknameProjection(
                userIdentifier,
                nickname
        );
    }

    private record TestUserIdentifierNicknameProjection(
            String userIdentifier,
            String nickname
    ) implements UserIdentifierNicknameProjection {

        @Override
        public String getUserIdentifier() {
            return userIdentifier;
        }

        @Override
        public String getNickname() {
            return nickname;
        }
    }
}