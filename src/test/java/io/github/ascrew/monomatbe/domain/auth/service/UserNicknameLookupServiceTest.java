package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserIdentifierNicknameProjection;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserNicknameLookupService의 userIdentifier -> nickname 조회 정책을 검증한다.
 */
class UserNicknameLookupServiceTest {

    private final GuestSessionRepository guestSessionRepository = mock(GuestSessionRepository.class);
    private final UserSessionRepository userSessionRepository = mock(UserSessionRepository.class);

    private final UserNicknameLookupService userNicknameLookupService = new UserNicknameLookupService(
            guestSessionRepository,
            userSessionRepository
    );

    @Test
    @DisplayName("userIdentifiers가 null이면 빈 Map을 반환하고 Repository를 호출하지 않는다")
    void findNicknameMapByUserIdentifiers_returnsEmptyMapWhenUserIdentifiersIsNull() {
        // when
        Map<String, String> result = userNicknameLookupService.findNicknameMapByUserIdentifiers(null);

        // then
        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).findNicknamesByGuestTokenIn(any());
        verify(userSessionRepository, never()).findNicknamesBySessionIdIn(any());
    }

    @Test
    @DisplayName("userIdentifiers가 비어 있으면 빈 Map을 반환하고 Repository를 호출하지 않는다")
    void findNicknameMapByUserIdentifiers_returnsEmptyMapWhenUserIdentifiersIsEmpty() {
        // when
        Map<String, String> result = userNicknameLookupService.findNicknameMapByUserIdentifiers(List.of());

        // then
        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).findNicknamesByGuestTokenIn(any());
        verify(userSessionRepository, never()).findNicknamesBySessionIdIn(any());
    }

    @Test
    @DisplayName("게스트와 회원 닉네임 Projection을 userIdentifier 기준 Map으로 변환한다")
    void findNicknameMapByUserIdentifiers_resolvesGuestAndRegisteredUserNicknames() {
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
        Map<String, String> result =
                userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", "게스트닉네임")
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(2);
    }

    @Test
    @DisplayName("게스트 닉네임 조회가 실패해도 회원 닉네임 조회 결과는 반환한다")
    void findNicknameMapByUserIdentifiers_continuesWhenGuestLookupFails() {
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
        Map<String, String> result =
                userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("회원 닉네임 조회가 실패해도 게스트 닉네임 조회 결과는 반환한다")
    void findNicknameMapByUserIdentifiers_continuesWhenRegisteredUserLookupFails() {
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
        Map<String, String> result =
                userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", "게스트닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("빈 userIdentifier 또는 빈 nickname Projection은 결과에서 제외한다")
    void findNicknameMapByUserIdentifiers_ignoresInvalidProjectionValues() {
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
        Map<String, String> result =
                userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("valid-identifier", "정상닉네임")
                .hasSize(1);
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