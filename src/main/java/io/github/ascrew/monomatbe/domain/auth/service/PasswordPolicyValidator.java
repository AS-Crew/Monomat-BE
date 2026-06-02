package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 정책 검증기
 *
 * [책임]
 * - 회원가입, 비밀번호 변경 등 비밀번호를 새로 설정하는 유스케이스의 공통 정책을 검증한다.
 *
 * [정책]
 * - null / blank 불가
 * - 공백 문자 포함 불가
 * - 8자 이상 100자 이하
 *
 * [주의]
 * 로그인 시점의 비밀번호 검증에는 사용하지 않는다.
 * 로그인은 기존 계정 호환성을 위해 null / blank만 차단하고,
 * 실제 인증 실패는 PasswordEncoder.matches() 결과로 판단한다.
 */
@Component
public class PasswordPolicyValidator {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 100;

    public String validateNewPassword(String rawPassword) {
        String password = normalizeRequired(rawPassword);

        if (containsWhitespace(password)) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_CONTAINS_WHITESPACE);
        }

        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_INVALID_LENGTH);
        }

        return password;
    }

    private String normalizeRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_REQUIRED);
        }

        return value;
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}