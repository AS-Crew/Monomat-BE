package io.github.ascrew.monomatbe.domain.user.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.auth.service.PasswordPolicyValidator;
import io.github.ascrew.monomatbe.domain.auth.service.UserSessionLifecycleService;
import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfileCacheEvictor;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.dto.UpdateNicknameRequest;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserCommandServiceTest {

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
        User user = User.builder()
                .id(1L)
                .username("oldNickname")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("newNickname")).thenReturn(false);

        MyUserInfoResponse response = userCommandService.updateMyNickname(
                principal,
                new UpdateNicknameRequest("newNickname")
        );

        assertEquals(1L, response.userId());
        assertEquals("newNickname", response.username());
        assertEquals(UserType.REGISTERED, response.userType());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertEquals("newNickname", user.getUsername());
    }

    @Test
    @DisplayName("기존 닉네임과 동일하면 중복 검증과 캐시 무효화를 수행하지 않는다")
    void updateMyNickname_sameNickname_returnsCurrentInfo() {
        User user = User.builder()
                .id(1L)
                .username("sameNickname")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        MyUserInfoResponse response = userCommandService.updateMyNickname(
                principal,
                new UpdateNicknameRequest("sameNickname")
        );

        assertEquals("sameNickname", response.username());
        verify(userRepository, never()).existsByUsername("sameNickname");
        verify(chatSenderProfileCacheEvictor, never()).evictByUserId(1L);
    }

    @Test
    @DisplayName("게스트 사용자는 닉네임을 변경할 수 없다")
    void updateMyNickname_guestUser_forbidden() {
        User user = User.builder()
                .id(1L)
                .username("guest")
                .userType(UserType.GUEST)
                .status(UserStatus.ACTIVE)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.GUEST,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        principal,
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(org.springframework.http.HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    @DisplayName("정지된 사용자는 닉네임을 변경할 수 없다")
    void updateMyNickname_bannedUser_forbidden() {
        User user = User.builder()
                .id(1L)
                .username("banned")
                .userType(UserType.REGISTERED)
                .status(UserStatus.BANNED)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        principal,
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(org.springframework.http.HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    @DisplayName("삭제된 사용자는 닉네임을 변경할 수 없다")
    void updateMyNickname_deletedUser_unauthorized() {
        User user = User.builder()
                .id(1L)
                .username("deleted")
                .userType(UserType.REGISTERED)
                .status(UserStatus.DELETED)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        principal,
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(org.springframework.http.HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    @DisplayName("중복 닉네임이면 409 Conflict가 발생한다")
    void updateMyNickname_duplicatedNickname_conflict() {
        User user = User.builder()
                .id(1L)
                .username("oldNickname")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByUsername("duplicated")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        principal,
                        new UpdateNicknameRequest("duplicated")
                )
        );

        assertSame(org.springframework.http.HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    @DisplayName("닉네임이 blank이면 400 Bad Request가 발생한다")
    void updateMyNickname_blankNickname_badRequest() {
        User user = User.builder()
                .id(1L)
                .username("oldNickname")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build();

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                user.getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        principal,
                        new UpdateNicknameRequest("   ")
                )
        );

        assertSame(org.springframework.http.HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    @DisplayName("인증 주체가 없으면 401 Unauthorized가 발생한다")
    void updateMyNickname_nullPrincipal_unauthorized() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        null,
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(org.springframework.http.HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 401 Unauthorized가 발생한다")
    void updateMyNickname_userNotFound_unauthorized() {
        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "session-id",
                UserType.REGISTERED,
                User.builder()
                        .userType(UserType.REGISTERED)
                        .build()
                        .getRole()
        );

        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> userCommandService.updateMyNickname(
                        principal,
                        new UpdateNicknameRequest("newNickname")
                )
        );

        assertSame(org.springframework.http.HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }
}