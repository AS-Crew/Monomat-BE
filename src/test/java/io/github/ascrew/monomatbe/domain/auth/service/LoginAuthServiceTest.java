package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

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

    @Test
    void login_success() {
        String loginId = "member_" + System.nanoTime();
        String password = "password123";
        String nickname = "n" + (System.nanoTime() % 1_000_000);
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
        String loginId = "member_" + System.nanoTime();
        createCredential(loginId, "password123", "n" + (System.nanoTime() % 1_000_000));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                loginAuthService.login(loginId, "wrong-password", null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        assertEquals("로그인 ID 또는 비밀번호가 올바르지 않습니다.", exception.getReason());

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(1, savedCredential.getFailedLoginCount());
        assertNull(savedCredential.getLockedUntil());
    }

    @Test
    void login_fiveFailures_locksAccount() {
        String loginId = "member_" + System.nanoTime();
        createCredential(loginId, "password123", "n" + (System.nanoTime() % 1_000_000));

        for (int i = 0; i < 5; i++) {
            assertThrows(ResponseStatusException.class, () ->
                    loginAuthService.login(loginId, "wrong-password", null, null));
        }

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(5, savedCredential.getFailedLoginCount());
        assertNotNull(savedCredential.getLockedUntil());
        assertTrue(savedCredential.getLockedUntil().isAfter(LocalDateTime.now()));

        ResponseStatusException lockedException = assertThrows(ResponseStatusException.class, () ->
                loginAuthService.login(loginId, "password123", null, null));
        assertEquals(HttpStatus.LOCKED, lockedException.getStatusCode());
    }

    @Test
    void login_lockedAccount_returnsLocked() {
        String loginId = "member_" + System.nanoTime();
        UserCredential credential = createCredential(loginId, "password123", "n" + (System.nanoTime() % 1_000_000));
        credential.lockUntil(LocalDateTime.now().plusMinutes(10));
        userCredentialRepository.saveAndFlush(credential);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                loginAuthService.login(loginId, "password123", null, null));
        assertEquals(HttpStatus.LOCKED, exception.getStatusCode());
        assertEquals("로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요.", exception.getReason());
    }

    @Test
    void login_success_resetsFailedState() {
        String loginId = "member_" + System.nanoTime();
        String password = "password123";
        UserCredential credential = createCredential(loginId, password, "n" + (System.nanoTime() % 1_000_000));
        credential.increaseFailedLoginCount();
        credential.increaseFailedLoginCount();
        credential.lockUntil(LocalDateTime.now().minusMinutes(1));
        userCredentialRepository.saveAndFlush(credential);

        loginAuthService.login(loginId, password, null, null);

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(0, savedCredential.getFailedLoginCount());
        assertNull(savedCredential.getLockedUntil());
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
