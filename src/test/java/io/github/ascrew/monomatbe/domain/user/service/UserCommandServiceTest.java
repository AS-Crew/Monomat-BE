package io.github.ascrew.monomatbe.domain.user.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.auth.service.PasswordPolicyValidator;
import io.github.ascrew.monomatbe.domain.auth.service.UserSessionLifecycleService;
import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfileCacheEvictor;
import io.github.ascrew.monomatbe.domain.user.dto.ChangePasswordRequest;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.dto.UpdateNicknameRequest;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-id";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserCredentialRepository userCredentialRepository = mock(UserCredentialRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PasswordPolicyValidator passwordPolicyValidator = new PasswordPolicyValidator();
    private final UserSessionLifecycleService userSessionLifecycleService = mock(UserSessionLifecycleService.class);
    private final ChatSenderProfileCacheEvictor chatSenderProfileCacheEvictor = mock(ChatSenderProfileCacheEvictor.class);

    private final UserCommandService userCommandService = new UserCommandService(
            userRepository,
            userCredentialRepository,
            passwordEncoder,
            passwordPolicyValidator,
            userSessionLifecycleService,
            chatSenderProfileCacheEvictor
    );

    @Test
    @DisplayName("정식 회원은 닉네임을 변경할 수 있다")
    void updateMyNickname_registeredUser_success() {
        User user = registeredUser("oldNickname");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("newNickname")).thenReturn(false);

        MyUserInfoResponse response = userCommandService.updateMyNickname(
                registeredPrincipal(),
                new UpdateNicknameRequest("newNickname")
        );

        assertEquals(USER_ID, response.userId());
        assertEquals("newNickname", response.username());
        assertEquals(UserType.REGISTERED, response.userType());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertEquals("newNickname", user.getUsername());
    }

    @Test
    @DisplayName("기존 닉네임과 동일하면 중복 검증과 캐시 무효화를 수행하지 않는다")
    void updateMyNickname_sameNickname_returnsCurrentInfo() {
        User user = registeredUser("sameNickname");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        MyUserInfoResponse response = userCommandService.updateMyNickname(
                registeredPrincipal(),
                new UpdateNicknameRequest("sameNickname")
        );

        assertEquals("sameNickname", response.username());
        verify(userRepository, never()).existsByUsername("sameNickname");
        verify(chatSenderProfileCacheEvictor, never()).evictByUserId(USER_ID);
    }

    @Test
    @DisplayName("게스트 사용자는 닉네임을 변경할 수 없다")
    void updateMyNickname_guestUser_forbidden() {
        User user = guestUser();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        guestPrincipal(),
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    @DisplayName("정지된 사용자는 닉네임을 변경할 수 없다")
    void updateMyNickname_bannedUser_forbidden() {
        User user = User.builder()
                .id(USER_ID)
                .username("banned")
                .userType(UserType.REGISTERED)
                .status(UserStatus.BANNED)
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        registeredPrincipal(),
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    @DisplayName("삭제된 사용자는 닉네임을 변경할 수 없다")
    void updateMyNickname_deletedUser_unauthorized() {
        User user = User.builder()
                .id(USER_ID)
                .username("deleted")
                .userType(UserType.REGISTERED)
                .status(UserStatus.DELETED)
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        registeredPrincipal(),
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    @DisplayName("중복 닉네임이면 409 Conflict가 발생한다")
    void updateMyNickname_duplicatedNickname_conflict() {
        User user = registeredUser("oldNickname");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("duplicated")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        registeredPrincipal(),
                        new UpdateNicknameRequest("duplicated")
                )
        );

        assertSame(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    @DisplayName("닉네임이 blank이면 400 Bad Request가 발생한다")
    void updateMyNickname_blankNickname_badRequest() {
        User user = registeredUser("oldNickname");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        registeredPrincipal(),
                        new UpdateNicknameRequest("   ")
                )
        );

        assertSame(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    @DisplayName("인증 주체가 없으면 닉네임 변경 시 401 Unauthorized가 발생한다")
    void updateMyNickname_nullPrincipal_unauthorized() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        null,
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 닉네임 변경 시 401 Unauthorized가 발생한다")
    void updateMyNickname_userNotFound_unauthorized() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        registeredPrincipal(),
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    @DisplayName("정식 회원은 현재 비밀번호 확인 후 새 비밀번호로 변경할 수 있다")
    void changeMyPassword_registeredUser_success() {
        User user = registeredUser("member");
        UserCredential credential = userCredential(user, "encoded-old-password");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userCredentialRepository.findByUser_Id(USER_ID)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("oldPassword123", "encoded-old-password")).thenReturn(true);
        when(passwordEncoder.encode("newPassword123")).thenReturn("encoded-new-password");

        userCommandService.changeMyPassword(
                registeredPrincipal(),
                new ChangePasswordRequest(
                        "oldPassword123",
                        "newPassword123",
                        "newPassword123"
                )
        );

        assertEquals("encoded-new-password", credential.getPasswordHash());
        assertNotNull(credential.getPasswordChangedAt());
        assertEquals(0, credential.getFailedLoginCount());
        verify(userSessionLifecycleService).revokeAllActiveSessions(
                USER_ID,
                credential.getPasswordChangedAt()
        );
    }

    @Test
    @DisplayName("게스트 사용자는 비밀번호를 변경할 수 없다")
    void changeMyPassword_guestUser_forbidden() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> userCommandService.changeMyPassword(
                        guestPrincipal(),
                        new ChangePasswordRequest(
                                "oldPassword123",
                                "newPassword123",
                                "newPassword123"
                        )
                )
        );

        assertEquals(AuthErrorCode.AUTH_REGISTERED_USER_ONLY, exception.getErrorCode());
        verify(userRepository, never()).findById(any());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    @Test
    @DisplayName("현재 비밀번호가 일치하지 않으면 비밀번호를 변경할 수 없다")
    void changeMyPassword_currentPasswordMismatch_unauthorized() {
        User user = registeredUser("member");
        UserCredential credential = userCredential(user, "encoded-old-password");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userCredentialRepository.findByUser_Id(USER_ID)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("wrongPassword123", "encoded-old-password")).thenReturn(false);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> userCommandService.changeMyPassword(
                        registeredPrincipal(),
                        new ChangePasswordRequest(
                                "wrongPassword123",
                                "newPassword123",
                                "newPassword123"
                        )
                )
        );

        assertEquals(AuthErrorCode.AUTH_CURRENT_PASSWORD_MISMATCH, exception.getErrorCode());
        assertEquals("encoded-old-password", credential.getPasswordHash());
        verify(passwordEncoder, never()).encode(any());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    @Test
    @DisplayName("새 비밀번호와 새 비밀번호 확인이 일치하지 않으면 비밀번호를 변경할 수 없다")
    void changeMyPassword_newPasswordConfirmMismatch_badRequest() {
        User user = registeredUser("member");
        UserCredential credential = userCredential(user, "encoded-old-password");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userCredentialRepository.findByUser_Id(USER_ID)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("oldPassword123", "encoded-old-password")).thenReturn(true);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> userCommandService.changeMyPassword(
                        registeredPrincipal(),
                        new ChangePasswordRequest(
                                "oldPassword123",
                                "newPassword123",
                                "differentPassword123"
                        )
                )
        );

        assertEquals(AuthErrorCode.AUTH_NEW_PASSWORD_CONFIRM_MISMATCH, exception.getErrorCode());
        assertEquals("encoded-old-password", credential.getPasswordHash());
        verify(passwordEncoder, never()).encode(any());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    @Test
    @DisplayName("새 비밀번호가 정책 길이보다 짧으면 비밀번호를 변경할 수 없다")
    void changeMyPassword_newPasswordTooShort_badRequest() {
        User user = registeredUser("member");
        UserCredential credential = userCredential(user, "encoded-old-password");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userCredentialRepository.findByUser_Id(USER_ID)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("oldPassword123", "encoded-old-password")).thenReturn(true);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> userCommandService.changeMyPassword(
                        registeredPrincipal(),
                        new ChangePasswordRequest(
                                "oldPassword123",
                                "short",
                                "short"
                        )
                )
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_INVALID_LENGTH, exception.getErrorCode());
        assertEquals("encoded-old-password", credential.getPasswordHash());
        verify(passwordEncoder, never()).encode(any());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    @Test
    @DisplayName("새 비밀번호에 공백이 포함되면 비밀번호를 변경할 수 없다")
    void changeMyPassword_newPasswordContainsWhitespace_badRequest() {
        User user = registeredUser("member");
        UserCredential credential = userCredential(user, "encoded-old-password");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userCredentialRepository.findByUser_Id(USER_ID)).thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("oldPassword123", "encoded-old-password")).thenReturn(true);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> userCommandService.changeMyPassword(
                        registeredPrincipal(),
                        new ChangePasswordRequest(
                                "oldPassword123",
                                "new password123",
                                "new password123"
                        )
                )
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE, exception.getErrorCode());
        assertEquals("encoded-old-password", credential.getPasswordHash());
        verify(passwordEncoder, never()).encode(any());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    @Test
    @DisplayName("비밀번호 변경 요청 본문이 null이면 실패한다")
    void changeMyPassword_nullRequest_badRequest() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> userCommandService.changeMyPassword(
                        registeredPrincipal(),
                        null
                )
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_REQUEST_BODY, exception.getErrorCode());
        verify(userRepository, never()).findById(any());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    @Test
    @DisplayName("인증정보가 없으면 비밀번호를 변경할 수 없다")
    void changeMyPassword_credentialNotFound_unauthorized() {
        User user = registeredUser("member");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userCredentialRepository.findByUser_Id(USER_ID)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.changeMyPassword(
                        registeredPrincipal(),
                        new ChangePasswordRequest(
                                "oldPassword123",
                                "newPassword123",
                                "newPassword123"
                        )
                )
        );

        assertSame(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verify(userSessionLifecycleService, never()).revokeAllActiveSessions(any(), any());
    }

    private CustomPrincipal registeredPrincipal() {
        return new CustomPrincipal(
                USER_ID,
                SESSION_ID,
                UserType.REGISTERED,
                UserRole.USER
        );
    }

    private CustomPrincipal guestPrincipal() {
        return new CustomPrincipal(
                USER_ID,
                SESSION_ID,
                UserType.GUEST,
                UserRole.USER
        );
    }

    private User registeredUser(String username) {
        return User.builder()
                .id(USER_ID)
                .username(username)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();
    }

    private User guestUser() {
        return User.builder()
                .id(USER_ID)
                .username("guest")
                .userType(UserType.GUEST)
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();
    }

    private UserCredential userCredential(User user, String passwordHash) {
        return UserCredential.builder()
                .id(1L)
                .user(user)
                .loginId("loginId1")
                .passwordHash(passwordHash)
                .failedLoginCount(3)
                .lockedUntil(LocalDateTime.now().plusMinutes(10))
                .build();
    }
}