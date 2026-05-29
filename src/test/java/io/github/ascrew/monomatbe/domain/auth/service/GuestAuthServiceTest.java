package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GuestAuthServiceTest {

    private UserRepository userRepository;
    private GuestSessionRepository guestSessionRepository;
    private UserSessionRepository userSessionRepository;
    private StringRedisTemplate redisTemplate;
    private JwtTokenProvider jwtTokenProvider;
    private UserSessionLifecycleService userSessionLifecycleService;
    private NicknamePolicyValidator nicknamePolicyValidator;
    private GuestAuthService guestAuthService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        guestSessionRepository = mock(GuestSessionRepository.class);
        userSessionRepository = mock(UserSessionRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        userSessionLifecycleService = mock(UserSessionLifecycleService.class);
        nicknamePolicyValidator = mock(NicknamePolicyValidator.class);

        guestAuthService = new GuestAuthService(
                userRepository,
                guestSessionRepository,
                userSessionRepository,
                redisTemplate,
                jwtTokenProvider,
                userSessionLifecycleService,
                nicknamePolicyValidator
        );
    }

    @Test
    @DisplayName("게스트 닉네임이 1자이면 길이 검증에서 실패한다")
    void guestLoginWithTooShortNickname() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> guestAuthService.loginAsGuest("가", "127.0.0.1", "test-agent")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH, exception.getErrorCode());
        verifyNoInteractions(nicknamePolicyValidator);
    }

    @Test
    @DisplayName("게스트 닉네임이 13자 이상이면 길이 검증에서 실패한다")
    void guestLoginWithTooLongNickname() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> guestAuthService.loginAsGuest("가나다라마바사아자차카타파", "127.0.0.1", "test-agent")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH, exception.getErrorCode());
        verifyNoInteractions(nicknamePolicyValidator);
    }

    @Test
    @DisplayName("게스트 닉네임에 금칙어가 포함되면 실패한다")
    void guestLoginWithForbiddenNickname() {
        doThrow(new AuthException(AuthErrorCode.AUTH_NICKNAME_FORBIDDEN_WORD))
                .when(nicknamePolicyValidator)
                .validate("관리자123");

        AuthException exception = assertThrows(
                AuthException.class,
                () -> guestAuthService.loginAsGuest("관리자123", "127.0.0.1", "test-agent")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_FORBIDDEN_WORD, exception.getErrorCode());
        verify(nicknamePolicyValidator).validate("관리자123");
        verifyNoInteractions(
                userRepository,
                guestSessionRepository,
                userSessionRepository,
                redisTemplate,
                jwtTokenProvider,
                userSessionLifecycleService
        );
    }
}