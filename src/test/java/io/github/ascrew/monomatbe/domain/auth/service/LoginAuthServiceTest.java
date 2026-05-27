package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class LoginAuthServiceTest {

    @Autowired
    private LoginAuthService loginAuthService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String uniqueLoginId() {
        return "member" + System.nanoTime();
    }

    private String uniqueNickname() {
        return "n" + (System.nanoTime() % 1_000_000);
    }

    @Test
    void login_success() {
        String loginId = uniqueLoginId();
        String password = "password123";
        String nickname = uniqueNickname();
        long sessionCountBefore = userSessionRepository.count();

        UserCredential credential = createCredential(loginId, password, nickname);

        LoginResponse response = loginAuthService.login(
                loginId,
                password,
                "127.0.0.1",
                "JUnit-Agent"
        );

        assertEquals(credential.getUser().getId(), response.userId());
        assertEquals(loginId, response.loginId());
        assertEquals(nickname, response.nickname());
        assertEquals(UserType.REGISTERED, response.userType());
        assertNotNull(response.accessToken());
        assertNotNull(response.refreshToken());
        assertNotNull(response.accessTokenExpiresAt());
        assertNotNull(response.refreshTokenExpiresAt());
        assertNotNull(response.userIdentifier());
        assertEquals(UUID.fromString(response.userIdentifier()).toString(), response.userIdentifier());

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(0, savedCredential.getFailedLoginCount());
        assertNull(savedCredential.getLockedUntil());

        User savedUser = userRepository.findById(response.userId()).orElseThrow();
        assertNotNull(savedUser.getLastLoginAt());

        assertEquals(sessionCountBefore + 1, userSessionRepository.count());
    }

    @Test
    void login_wrongPassword_incrementsFailedCount() {
        String loginId = uniqueLoginId();

        createCredential(loginId, "password123", uniqueNickname());

        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, "wrong-password", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(1, savedCredential.getFailedLoginCount());
        assertNull(savedCredential.getLockedUntil());
    }

    @Test
    void login_fiveFailures_locksAccount() {
        String loginId = uniqueLoginId();

        createCredential(loginId, "password123", uniqueNickname());

        for (int i = 0; i < 5; i++) {
            AuthException exception = assertThrows(AuthException.class, () ->
                    loginAuthService.login(loginId, "wrong-password", null, null)
            );

            assertEquals(AuthErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
        }

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(5, savedCredential.getFailedLoginCount());
        assertNotNull(savedCredential.getLockedUntil());
        assertTrue(savedCredential.getLockedUntil().isAfter(LocalDateTime.now()));

        AuthException lockedException = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, "password123", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_ACCOUNT_LOCKED, lockedException.getErrorCode());
    }

    @Test
    void login_lockedAccount_returnsLockedError() {
        String loginId = uniqueLoginId();

        UserCredential credential = createCredential(loginId, "password123", uniqueNickname());
        credential.lockUntil(LocalDateTime.now().plusMinutes(10));
        userCredentialRepository.saveAndFlush(credential);

        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, "password123", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_ACCOUNT_LOCKED, exception.getErrorCode());
    }

    @Test
    void login_success_resetsFailedState() {
        String loginId = uniqueLoginId();
        String password = "password123";

        UserCredential credential = createCredential(loginId, password, uniqueNickname());
        credential.increaseFailedLoginCount();
        credential.increaseFailedLoginCount();
        credential.lockUntil(LocalDateTime.now().minusMinutes(1));
        userCredentialRepository.saveAndFlush(credential);

        loginAuthService.login(loginId, password, null, null);

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(0, savedCredential.getFailedLoginCount());
        assertNull(savedCredential.getLockedUntil());
    }

    @Test
    void login_blankLoginId_throwsLoginIdRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login("   ", "password123", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_REQUIRED, exception.getErrorCode());
    }

    @Test
    void login_loginIdInvalidFormat_throwsLoginIdInvalidFormatError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login("abcd!", "password123", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_INVALID_FORMAT, exception.getErrorCode());
    }

    @Test
    void login_passwordWithWhitespace_throwsPasswordWhitespaceError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(uniqueLoginId(), "pass word123", null, null)
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE, exception.getErrorCode());
    }

    private UserCredential createCredential(String loginId, String rawPassword, String nickname) {
        User savedUser = userRepository.saveAndFlush(User.builder()
                .username(nickname)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        return userCredentialRepository.saveAndFlush(UserCredential.builder()
                .user(savedUser)
                .loginId(loginId)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .build());
    }
}