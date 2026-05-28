package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NicknamePolicyValidatorTest {

    @Test
    @DisplayName("금칙어가 포함된 닉네임이면 AuthException을 던진다")
    void validateForbiddenNickname() {
        ForbiddenNicknameService forbiddenNicknameService = mock(ForbiddenNicknameService.class);
        when(forbiddenNicknameService.containsForbiddenWord("관리자123")).thenReturn(true);

        NicknamePolicyValidator validator = new NicknamePolicyValidator(forbiddenNicknameService);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> validator.validate("관리자123")
        );

        assertEquals(AuthErrorCode.AUTH_NICKNAME_FORBIDDEN_WORD, exception.getErrorCode());
    }

    @Test
    @DisplayName("금칙어가 포함되지 않은 닉네임이면 예외를 던지지 않는다")
    void validateAllowedNickname() {
        ForbiddenNicknameService forbiddenNicknameService = mock(ForbiddenNicknameService.class);
        when(forbiddenNicknameService.containsForbiddenWord("정상닉네임")).thenReturn(false);

        NicknamePolicyValidator validator = new NicknamePolicyValidator(forbiddenNicknameService);

        assertDoesNotThrow(() -> validator.validate("정상닉네임"));
    }
}