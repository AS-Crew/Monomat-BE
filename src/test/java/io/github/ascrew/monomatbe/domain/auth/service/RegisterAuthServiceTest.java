package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RegisterResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RegisterAuthServiceTest {

    @Autowired
    private RegisterAuthService registerAuthService;

    @Autowired
    private UserRepository userRepository;

    private String uniqueLoginId() {
        return "member" + System.nanoTime();
    }

    private String uniqueNickname() {
        return "n" + (System.nanoTime() % 1_000_000);
    }

    @Test
    void register_success() {
        String loginId = uniqueLoginId();
        String nickname = uniqueNickname();

        RegisterResponse response = registerAuthService.register(
                loginId,
                "password123",
                nickname
        );

        assertNotNull(response.userId());
        assertEquals(loginId, response.loginId());
        assertEquals(nickname, response.nickname());
        assertEquals(UserType.REGISTERED, response.userType());
    }

    @Test
    void register_trimsLoginIdAndNickname() {
        String loginId = uniqueLoginId();
        String nickname = uniqueNickname();

        RegisterResponse response = registerAuthService.register(
                "  " + loginId + "  ",
                "password123",
                "  " + nickname + "  "
        );

        assertEquals(loginId, response.loginId());
        assertEquals(nickname, response.nickname());
    }

    @Test
    void register_passwordWithWhitespace_throwsPasswordWhitespaceError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(uniqueLoginId(), "  password123  ", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE, exception.getErrorCode());
    }

    @Test
    void register_duplicateLoginId_throwsDuplicatedLoginIdError() {
        String loginId = uniqueLoginId();

        registerAuthService.register(loginId, "password123", uniqueNickname());

        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(loginId, "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_DUPLICATED, exception.getErrorCode());
    }

    @Test
    void register_duplicateNickname_throwsDuplicatedNicknameError() {
        String nickname = uniqueNickname();

        userRepository.saveAndFlush(User.builder()
                .username(nickname)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(uniqueLoginId(), "password123", nickname)
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_DUPLICATED, exception.getErrorCode());
    }

    @Test
    void register_nullPassword_throwsPasswordRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(uniqueLoginId(), null, uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_PASSWORD_REQUIRED, exception.getErrorCode());
    }

    @Test
    void register_blankLoginId_throwsLoginIdRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register("   ", "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_REQUIRED, exception.getErrorCode());
    }

    @Test
    void register_loginIdTooShort_throwsLoginIdInvalidLengthError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register("abc", "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_INVALID_LENGTH, exception.getErrorCode());
    }

    @Test
    void register_loginIdTooLong_throwsLoginIdInvalidLengthError() {
        String tooLongLoginId = "a".repeat(51);

        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(tooLongLoginId, "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_INVALID_LENGTH, exception.getErrorCode());
    }

    @Test
    void register_loginIdWithWhitespace_throwsLoginIdWhitespaceError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register("abc def", "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_CONTAINS_WHITESPACE, exception.getErrorCode());
    }

    @Test
    void register_loginIdInvalidFormat_throwsLoginIdInvalidFormatError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register("abcd!", "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_INVALID_FORMAT, exception.getErrorCode());
    }

    @Test
    void register_nicknameTooShort_throwsNicknameInvalidLengthError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(uniqueLoginId(), "password123", "a")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH, exception.getErrorCode());
    }

    @Test
    void register_nicknameTooLong_throwsNicknameInvalidLengthError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(uniqueLoginId(), "password123", "1234567890123")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH, exception.getErrorCode());
    }

    @Test
    void register_nullLoginId_throwsLoginIdRequiredError() {
        AuthException exception = assertThrows(AuthException.class, () ->
                registerAuthService.register(null, "password123", uniqueNickname())
        );

        assertEquals(AuthErrorCode.AUTH_LOGIN_ID_REQUIRED, exception.getErrorCode());
    }
}