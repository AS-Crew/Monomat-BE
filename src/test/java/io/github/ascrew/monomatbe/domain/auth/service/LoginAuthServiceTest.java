package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthLoginFailureException;
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
                false,
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
    void login_legacyLoginIdWithUnderscore_success() {
        String loginId = "legacy_" + System.nanoTime();
        String password = "password123";
        String nickname = uniqueNickname();

        UserCredential credential = createCredential(loginId, password, nickname);

        LoginResponse response = loginAuthService.login(
                loginId,
                password,
                false,
                "127.0.0.1",
                "JUnit-Agent"
        );

        assertEquals(credential.getUser().getId(), response.userId());
        assertEquals(loginId, response.loginId());
        assertEquals(nickname, response.nickname());
        assertEquals(UserType.REGISTERED, response.userType());
    }

    @Test
    void login_legacyLoginIdWithSpecialCharacter_success() {
        String loginId = "legacy-" + System.nanoTime();
        String password = "password123";
        String nickname = uniqueNickname();

        UserCredential credential = createCredential(loginId, password, nickname);

        LoginResponse response = loginAuthService.login(
                loginId,
                password,
                false,
                "127.0.0.1",
                "JUnit-Agent"
        );

        assertEquals(credential.getUser().getId(), response.userId());
        assertEquals(loginId, response.loginId());
        assertEquals(nickname, response.nickname());
        assertEquals(UserType.REGISTERED, response.userType());
    }

    @Test
    void login_unknownLoginId_returnsInvalidCredentials() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login("unknown!", "password123", false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void login_wrongPassword_incrementsFailedCount() {
        String loginId = uniqueLoginId();

        createCredential(loginId, "password123", uniqueNickname());

        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, "wrong-password", false, null, null)
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
                    loginAuthService.login(loginId, "wrong-password", false, null, null)
            );

            assertEquals(AuthErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
        }

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(5, savedCredential.getFailedLoginCount());
        assertNotNull(savedCredential.getLockedUntil());
        assertTrue(savedCredential.getLockedUntil().isAfter(LocalDateTime.now()));

        AuthException lockedException = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, "password123", false, null, null)
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
                loginAuthService.login(loginId, "password123", false, null, null)
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

        loginAuthService.login(loginId, password, false, null, null);

        UserCredential savedCredential = userCredentialRepository.findByLoginId(loginId).orElseThrow();
        assertEquals(0, savedCredential.getFailedLoginCount());
        assertNull(savedCredential.getLockedUntil());
    }

    @Test
    void login_blankLoginId_throwsLoginIdRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login("   ", "password123", false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_REQUIRED, exception.getErrorCode());
    }

    @Test
    void login_nullLoginId_throwsLoginIdRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(null, "password123", false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_REQUIRED, exception.getErrorCode());
    }

    @Test
    void login_blankPassword_throwsPasswordRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(uniqueLoginId(), "   ", false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_REQUIRED, exception.getErrorCode());
    }

    @Test
    void login_nullPassword_throwsPasswordRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(uniqueLoginId(), null, false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_REQUIRED, exception.getErrorCode());
    }

    @Test
    void login_wrongPassword_throwsLoginFailureExceptionForNoRollback() {
        String loginId = uniqueLoginId();

        createCredential(loginId, "password123", uniqueNickname());

        AuthLoginFailureException exception = assertThrows(AuthLoginFailureException.class, () ->
                loginAuthService.login(loginId, "wrong-password", false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void login_passwordWithWhitespace_isHandledAsInvalidCredentialsAfterLookup() {
        String loginId = uniqueLoginId();

        createCredential(loginId, "password123", uniqueNickname());

        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, "pass word123", false, null, null)
        );

        assertEquals(AuthErrorCode.AUTH_INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void login_withExistingActiveSession_rejectsConcurrentLogin() {
        String loginId = uniqueLoginId();
        String password = "password123";

        createCredential(loginId, password, uniqueNickname());

        // 첫 로그인으로 활성 세션을 생성한다.
        loginAuthService.login(loginId, password, false, "127.0.0.1", "JUnit-Agent");

        // 활성 세션이 있는 상태에서 두 번째 로그인은 거부되어야 한다.
        AuthException exception = assertThrows(AuthException.class, () ->
                loginAuthService.login(loginId, password, false, "127.0.0.2", "JUnit-Agent-2")
        );

        assertEquals(AuthErrorCode.AUTH_CONCURRENT_LOGIN_REJECTED, exception.getErrorCode());
    }

    @Test
    void login_forceWithExistingActiveSession_revokesPreviousAndSucceeds() {
        String loginId = uniqueLoginId();
        String password = "password123";

        UserCredential credential = createCredential(loginId, password, uniqueNickname());
        Long userId = credential.getUser().getId();

        LoginResponse first = loginAuthService.login(loginId, password, false, "127.0.0.1", "JUnit-Agent");

        // force=true 재로그인은 기존 세션을 revoke하고 신규 로그인에 성공해야 한다.
        LoginResponse second = loginAuthService.login(loginId, password, true, "127.0.0.2", "JUnit-Agent-2");

        assertNotNull(second.accessToken());
        assertEquals(UserSessionStatus.REVOKED,
                userSessionRepository.findBySessionId(first.userIdentifier()).orElseThrow().getStatus());
        assertEquals(UserSessionStatus.ACTIVE,
                userSessionRepository.findBySessionId(second.userIdentifier()).orElseThrow().getStatus());

        long activeCount = userSessionRepository
                .findByUser_IdAndStatusOrderByCreatedAtAsc(userId, UserSessionStatus.ACTIVE).size();
        assertEquals(1, activeCount);
    }

    @Test
    void login_afterLogout_allowsReLogin() {
        String loginId = uniqueLoginId();
        String password = "password123";

        UserCredential credential = createCredential(loginId, password, uniqueNickname());
        LocalDateTime now = LocalDateTime.now();

        // 로그아웃(LOGOUT) 상태 세션만 존재하면 재로그인이 허용되어야 한다.
        createSession(credential.getUser(), UserSessionStatus.LOGOUT, now.plusDays(30), now);

        LoginResponse response = loginAuthService.login(loginId, password, false, "127.0.0.1", "JUnit-Agent");

        assertNotNull(response.accessToken());
    }

    @Test
    void login_afterSessionExpired_allowsReLogin() {
        String loginId = uniqueLoginId();
        String password = "password123";

        UserCredential credential = createCredential(loginId, password, uniqueNickname());
        LocalDateTime now = LocalDateTime.now();

        // status는 ACTIVE이지만 만료된(아직 정리되지 않은) 세션은 차단 대상이 아니다.
        createSession(credential.getUser(), UserSessionStatus.ACTIVE, now.minusMinutes(1), now.minusDays(31));

        LoginResponse response = loginAuthService.login(loginId, password, false, "127.0.0.1", "JUnit-Agent");

        assertNotNull(response.accessToken());
    }

    private void createSession(
            User user,
            UserSessionStatus status,
            LocalDateTime expiresAt,
            LocalDateTime createdAt
    ) {
        userSessionRepository.saveAndFlush(UserSession.builder()
                .user(user)
                .sessionId(UUID.randomUUID().toString())
                .sessionToken(UUID.randomUUID().toString())
                .ipAddress("127.0.0.1")
                .userAgent("JUnit-Agent")
                .expiresAt(expiresAt)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .status(status)
                .build());
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