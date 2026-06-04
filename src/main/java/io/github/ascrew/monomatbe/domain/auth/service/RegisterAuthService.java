package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RegisterResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 비즈니스 로직
 *
 * [에러 응답 정책]
 * 인증 도메인 에러는 AuthException + AuthErrorCode로 처리한다.
 * FE는 message가 아니라 code와 field를 기준으로 에러 UI를 제어한다.
 */
@Service
@RequiredArgsConstructor
public class RegisterAuthService {

    private static final int MIN_LOGIN_ID_LENGTH = 4;
    private static final int MAX_LOGIN_ID_LENGTH = 50;
    private static final int MIN_NICKNAME_LENGTH = 2;
    private static final int MAX_NICKNAME_LENGTH = 12;

    private static final String LOGIN_ID_PATTERN = "^[A-Za-z0-9]+$";

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final NicknamePolicyValidator nicknamePolicyValidator;
    private final PasswordPolicyValidator passwordPolicyValidator;

    @Transactional
    public RegisterResponse register(String rawLoginId, String rawPassword, String rawNickname) {
        String loginId = normalizeLoginId(rawLoginId);
        String password = passwordPolicyValidator.validateNewPassword(rawPassword);
        String nickname = normalizeNickname(rawNickname);

        nicknamePolicyValidator.validate(nickname);
        validateDuplicate(loginId, nickname);

        User savedUser;

        /*
         * user_credentials 저장 실패 시 @Transactional에 의해 users 저장도 함께 롤백된다.
         * DB unique 제약과 애플리케이션 중복 검증 사이의 race condition은
         * DataIntegrityViolationException을 AuthException으로 변환해 동일한 응답 계약을 유지한다.
         */
        try {
            savedUser = userRepository.saveAndFlush(User.builder()
                    .username(nickname)
                    .userType(UserType.REGISTERED)
                    .status(UserStatus.ACTIVE)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_DUPLICATED, e);
        }

        try {
            userCredentialRepository.saveAndFlush(UserCredential.builder()
                    .user(savedUser)
                    .loginId(loginId)
                    .passwordHash(passwordEncoder.encode(password))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_DUPLICATED, e);
        }

        return RegisterResponse.builder()
                .userId(savedUser.getId())
                .loginId(loginId)
                .nickname(savedUser.getUsername())
                .userType(savedUser.getUserType())
                .build();
    }

    private void validateDuplicate(String loginId, String nickname) {
        if (userCredentialRepository.existsByLoginId(loginId)) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_DUPLICATED);
        }

        if (userRepository.existsByUsername(nickname)) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_DUPLICATED);
        }
    }

    private String normalizeLoginId(String value) {
        String loginId = normalizeRequiredWithTrim(value, AuthErrorCode.AUTH_LOGIN_ID_REQUIRED);

        if (containsWhitespace(loginId)) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_CONTAINS_WHITESPACE);
        }

        if (loginId.length() < MIN_LOGIN_ID_LENGTH || loginId.length() > MAX_LOGIN_ID_LENGTH) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_INVALID_LENGTH);
        }

        if (!loginId.matches(LOGIN_ID_PATTERN)) {
            throw new AuthException(AuthErrorCode.AUTH_LOGIN_ID_INVALID_FORMAT);
        }

        return loginId;
    }

    private String normalizeNickname(String value) {
        String nickname = normalizeRequiredWithTrim(value, AuthErrorCode.AUTH_NICKNAME_REQUIRED);

        if (nickname.length() < MIN_NICKNAME_LENGTH || nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH);
        }

        return nickname;
    }

    private String normalizeRequiredWithTrim(String value, AuthErrorCode requiredErrorCode) {
        String normalized = normalizeRequired(value, requiredErrorCode).trim();

        if (normalized.isBlank()) {
            throw new AuthException(requiredErrorCode);
        }

        return normalized;
    }

    private String normalizeRequired(String value, AuthErrorCode requiredErrorCode) {
        if (value == null || value.isBlank()) {
            throw new AuthException(requiredErrorCode);
        }

        return value;
    }

    private boolean containsWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }
}