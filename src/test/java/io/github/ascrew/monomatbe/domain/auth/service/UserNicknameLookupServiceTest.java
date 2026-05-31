package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserIdentifierProfileProjection;
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
 * UserNicknameLookupService의 userIdentifier -> 사용자 표시 정보 조회 정책을 검증한다.
 */
class UserNicknameLookupServiceTest {

    private final GuestSessionRepository guestSessionRepository = mock(GuestSessionRepository.class);
    private final UserSessionRepository userSessionRepository = mock(UserSessionRepository.class);

    private final UserNicknameLookupService userNicknameLookupService = new UserNicknameLookupService(
            guestSessionRepository,
            userSessionRepository
    );

    @Test
    @DisplayName("userIdentifiers가 null이면 빈 닉네임 Map을 반환하고 Repository를 호출하지 않는다")
    void findNicknameMapByUserIdentifiers_returnsEmptyMapWhenUserIdentifiersIsNull() {
        // when
        Map<String, String> result = userNicknameLookupService.findNicknameMapByUserIdentifiers(null);

        // then
        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).findProfilesByGuestTokenIn(any());
        verify(userSessionRepository, never()).findProfilesBySessionIdIn(any());
    }

    @Test
    @DisplayName("userIdentifiers가 비어 있으면 빈 닉네임 Map을 반환하고 Repository를 호출하지 않는다")
    void findNicknameMapByUserIdentifiers_returnsEmptyMapWhenUserIdentifiersIsEmpty() {
        // when
        Map<String, String> result = userNicknameLookupService.findNicknameMapByUserIdentifiers(List.of());

        // then
        assertThat(result).isEmpty();
        verify(guestSessionRepository, never()).findProfilesByGuestTokenIn(any());
        verify(userSessionRepository, never()).findProfilesBySessionIdIn(any());
    }

    @Test
    @DisplayName("게스트와 회원 프로필 Projection을 userIdentifier 기준 닉네임 Map으로 변환한다")
    void findNicknameMapByUserIdentifiers_resolvesGuestAndRegisteredUserNicknames() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findProfilesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(profileProjection(
                        "guest-identifier",
                        1L,
                        "게스트닉네임"
                )));

        when(userSessionRepository.findProfilesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of(profileProjection(
                        "registered-identifier",
                        2L,
                        "회원닉네임"
                )));

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
    @DisplayName("게스트 프로필 조회가 실패해도 회원 프로필 조회 결과는 닉네임 Map으로 반환한다")
    void findNicknameMapByUserIdentifiers_continuesWhenGuestLookupFails() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findProfilesByGuestTokenIn(userIdentifiers))
                .thenThrow(new RuntimeException("guest lookup failed"));

        when(userSessionRepository.findProfilesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of(profileProjection(
                        "registered-identifier",
                        2L,
                        "회원닉네임"
                )));

        // when
        Map<String, String> result =
                userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("registered-identifier", "회원닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("회원 프로필 조회가 실패해도 게스트 프로필 조회 결과는 닉네임 Map으로 반환한다")
    void findNicknameMapByUserIdentifiers_continuesWhenRegisteredUserLookupFails() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findProfilesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(profileProjection(
                        "guest-identifier",
                        1L,
                        "게스트닉네임"
                )));

        when(userSessionRepository.findProfilesBySessionIdIn(userIdentifiers))
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
    @DisplayName("빈 userIdentifier, 빈 nickname, null userId Projection은 닉네임 Map 결과에서 제외한다")
    void findNicknameMapByUserIdentifiers_ignoresInvalidProjectionValues() {
        // given
        List<String> userIdentifiers = List.of(
                "valid-identifier",
                "blank-nickname",
                "blank-identifier",
                "null-user-id"
        );

        when(guestSessionRepository.findProfilesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(
                        profileProjection("valid-identifier", 1L, "정상닉네임"),
                        profileProjection("blank-nickname", 2L, " "),
                        profileProjection(" ", 3L, "식별자없음"),
                        profileProjection("null-user-id", null, "유저아이디없음")
                ));

        when(userSessionRepository.findProfilesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of());

        // when
        Map<String, String> result =
                userNicknameLookupService.findNicknameMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("valid-identifier", "정상닉네임")
                .hasSize(1);
    }

    @Test
    @DisplayName("게스트와 회원 프로필 Projection을 userIdentifier 기준 프로필 Map으로 변환한다")
    void findProfileMapByUserIdentifiers_resolvesGuestAndRegisteredUserProfiles() {
        // given
        List<String> userIdentifiers = List.of(
                "guest-identifier",
                "registered-identifier"
        );

        when(guestSessionRepository.findProfilesByGuestTokenIn(userIdentifiers))
                .thenReturn(List.of(profileProjection(
                        "guest-identifier",
                        1L,
                        "게스트닉네임"
                )));

        when(userSessionRepository.findProfilesBySessionIdIn(userIdentifiers))
                .thenReturn(List.of(profileProjection(
                        "registered-identifier",
                        2L,
                        "회원닉네임"
                )));

        // when
        Map<String, UserIdentifierProfile> result =
                userNicknameLookupService.findProfileMapByUserIdentifiers(userIdentifiers);

        // then
        assertThat(result)
                .containsEntry("guest-identifier", new UserIdentifierProfile(1L, "게스트닉네임"))
                .containsEntry("registered-identifier", new UserIdentifierProfile(2L, "회원닉네임"))
                .hasSize(2);
    }

    private UserIdentifierProfileProjection profileProjection(
            String userIdentifier,
            Long userId,
            String nickname
    ) {
        return new TestUserIdentifierProfileProjection(
                userIdentifier,
                userId,
                nickname
        );
    }

    private record TestUserIdentifierProfileProjection(
            String userIdentifier,
            Long userId,
            String nickname
    ) implements UserIdentifierProfileProjection {

        @Override
        public String getUserIdentifier() {
            return userIdentifier;
        }

        @Override
        public Long getUserId() {
            return userId;
        }

        @Override
        public String getNickname() {
            return nickname;
        }
    }
}