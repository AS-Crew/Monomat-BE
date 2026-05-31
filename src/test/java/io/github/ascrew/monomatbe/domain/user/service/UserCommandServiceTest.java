package io.github.ascrew.monomatbe.domain.user.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfileCacheEvictor;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.dto.UpdateNicknameRequest;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final String USER_IDENTIFIER = "session-id";
    private static final String CURRENT_NICKNAME = "기존닉네임";
    private static final String NEW_NICKNAME = "변경닉네임";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ChatSenderProfileCacheEvictor chatSenderProfileCacheEvictor =
            mock(ChatSenderProfileCacheEvictor.class);

    private final UserCommandService userCommandService = new UserCommandService(
            userRepository,
            chatSenderProfileCacheEvictor
    );

    @Test
    @DisplayName("정식 회원은 닉네임을 변경할 수 있다")
    void updateMyNickname_updatesRegisteredUserNickname() {
        // given
        User user = registeredUser(CURRENT_NICKNAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername(NEW_NICKNAME)).thenReturn(false);

        CustomPrincipal principal = principal();
        UpdateNicknameRequest request = new UpdateNicknameRequest(NEW_NICKNAME);

        // when
        MyUserInfoResponse response = userCommandService.updateMyNickname(principal, request);

        // then
        assertThat(user.getUsername()).isEqualTo(NEW_NICKNAME);
        assertThat(response.userId()).isEqualTo(USER_ID);
        assertThat(response.username()).isEqualTo(NEW_NICKNAME);
    }

    @Test
    @DisplayName("동일한 닉네임으로 변경 요청하면 중복 검사 없이 현재 정보를 반환한다")
    void updateMyNickname_returnsCurrentInfoWhenNicknameIsSame() {
        // given
        User user = registeredUser(CURRENT_NICKNAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CustomPrincipal principal = principal();
        UpdateNicknameRequest request = new UpdateNicknameRequest(CURRENT_NICKNAME);

        // when
        MyUserInfoResponse response = userCommandService.updateMyNickname(principal, request);

        // then
        assertThat(user.getUsername()).isEqualTo(CURRENT_NICKNAME);
        assertThat(response.username()).isEqualTo(CURRENT_NICKNAME);

        verify(userRepository, never()).existsByUsername(CURRENT_NICKNAME);
    }

    @Test
    @DisplayName("게스트는 닉네임을 변경할 수 없다")
    void updateMyNickname_rejectsGuestUser() {
        // given
        User user = guestUser(CURRENT_NICKNAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CustomPrincipal principal = principal();
        UpdateNicknameRequest request = new UpdateNicknameRequest(NEW_NICKNAME);

        // when & then
        assertThatThrownBy(() -> userCommandService.updateMyNickname(principal, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    @DisplayName("이미 사용 중인 닉네임이면 변경할 수 없다")
    void updateMyNickname_rejectsDuplicatedNickname() {
        // given
        User user = registeredUser(CURRENT_NICKNAME);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername(NEW_NICKNAME)).thenReturn(true);

        CustomPrincipal principal = principal();
        UpdateNicknameRequest request = new UpdateNicknameRequest(NEW_NICKNAME);

        // when & then
        assertThatThrownBy(() -> userCommandService.updateMyNickname(principal, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    @DisplayName("정지된 회원은 닉네임을 변경할 수 없다")
    void updateMyNickname_rejectsBannedUser() {
        // given
        User user = User.builder()
                .id(USER_ID)
                .username(CURRENT_NICKNAME)
                .userType(UserType.REGISTERED)
                .status(UserStatus.BANNED)
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        CustomPrincipal principal = principal();
        UpdateNicknameRequest request = new UpdateNicknameRequest(NEW_NICKNAME);

        // when & then
        assertThatThrownBy(() -> userCommandService.updateMyNickname(principal, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    @DisplayName("인증 주체가 없으면 닉네임을 변경할 수 없다")
    void updateMyNickname_rejectsInvalidPrincipal() {
        // given
        UpdateNicknameRequest request = new UpdateNicknameRequest(NEW_NICKNAME);

        // when & then
        assertThatThrownBy(() -> userCommandService.updateMyNickname(null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    private CustomPrincipal principal() {
        return new CustomPrincipal(
                USER_ID,
                USER_IDENTIFIER,
                UserType.REGISTERED
        );
    }

    private User registeredUser(String username) {
        return User.builder()
                .id(USER_ID)
                .username(username)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private User guestUser(String username) {
        return User.builder()
                .id(USER_ID)
                .username(username)
                .userType(UserType.GUEST)
                .status(UserStatus.ACTIVE)
                .build();
    }
}