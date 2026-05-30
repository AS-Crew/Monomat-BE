package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RegisterAuthServiceTest {

    private UserRepository userRepository;
    private UserCredentialRepository userCredentialRepository;
    private PasswordEncoder passwordEncoder;
    private NicknamePolicyValidator nicknamePolicyValidator;
    private RegisterAuthService registerAuthService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userCredentialRepository = mock(UserCredentialRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        nicknamePolicyValidator = mock(NicknamePolicyValidator.class);

        registerAuthService = new RegisterAuthService(
                userRepository,
                userCredentialRepository,
                passwordEncoder,
                nicknamePolicyValidator
        );
    }

    @Test
    @DisplayName("회원가입 닉네임이 1자이면 길이 검증에서 실패한다")
    void registerWithTooShortNickname() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> registerAuthService.register("loginId1", "password123", "가")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH, exception.getErrorCode());
        verifyNoInteractions(nicknamePolicyValidator);
    }

    @Test
    @DisplayName("회원가입 닉네임이 13자 이상이면 길이 검증에서 실패한다")
    void registerWithTooLongNickname() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> registerAuthService.register("loginId1", "password123", "가나다라마바사아자차카타파")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH, exception.getErrorCode());
        verifyNoInteractions(nicknamePolicyValidator);
    }

    @Test
    @DisplayName("회원가입 닉네임에 금칙어가 포함되면 실패한다")
    void registerWithForbiddenNickname() {
        doThrow(new AuthException(AuthErrorCode.AUTH_NICKNAME_FORBIDDEN_WORD))
                .when(nicknamePolicyValidator)
                .validate("관리자123");

        AuthException exception = assertThrows(
                AuthException.class,
                () -> registerAuthService.register("loginId1", "password123", "관리자123")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_FORBIDDEN_WORD, exception.getErrorCode());
        verify(nicknamePolicyValidator).validate("관리자123");
        verifyNoInteractions(userRepository, userCredentialRepository, passwordEncoder);
    }
}