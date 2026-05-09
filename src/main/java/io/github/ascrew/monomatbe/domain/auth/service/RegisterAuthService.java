package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RegisterResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회원가입 비즈니스 로직.
 */
@Service
@RequiredArgsConstructor
public class RegisterAuthService {

    private static final String ERR_DUPLICATE_LOGIN_ID = "이미 사용 중인 로그인 ID입니다.";
    private static final String ERR_DUPLICATE_NICKNAME = "이미 사용 중인 닉네임입니다.";
    private static final int MAX_NICKNAME_LENGTH = 8;

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterResponse register(String rawLoginId, String rawPassword, String rawNickname) {
        String loginId = normalizeRequiredWithTrim(rawLoginId, "로그인 ID");
        validateNoWhitespace(loginId, "로그인 ID");

        String password = validateNoWhitespace(rawPassword, "비밀번호");

        String nickname = normalizeRequiredWithTrim(rawNickname, "닉네임");
        validateNicknameLength(nickname);
        validateDuplicate(loginId, nickname);

        User savedUser;

        // user_credentials 저장 실패 시 @Transactional에 의해 users 저장도 함께 롤백됨
        // ResponseStatusException은 런타임 예외이므로 롤백 대상에 포함됨
        try {
            savedUser = userRepository.saveAndFlush(User.builder()
                    .username(nickname)
                    .userType(UserType.REGISTERED)
                    .status(UserStatus.ACTIVE)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERR_DUPLICATE_NICKNAME);
        }

        try {
            userCredentialRepository.saveAndFlush(UserCredential.builder()
                    .user(savedUser)
                    .loginId(loginId)
                    .passwordHash(passwordEncoder.encode(password))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERR_DUPLICATE_LOGIN_ID);
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERR_DUPLICATE_LOGIN_ID);
        }
        if (userRepository.existsByUsername(nickname)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERR_DUPLICATE_NICKNAME);
        }
    }

    /**
     * 서비스 직접 호출 경로를 위한 최소 방어선: trim + null/blank 차단.
     */
    private String normalizeRequiredWithTrim(String value, String fieldName) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "는 비어 있을 수 없습니다.");
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "는 비어 있을 수 없습니다.");
        }
        return normalized;
    }

    private String validateNoWhitespace(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "는 비어 있을 수 없습니다.");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "에는 공백을 포함할 수 없습니다.");
        }
        return value;
    }
    
    private void validateNicknameLength(String nickname) {
        if (nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 8자를 초과할 수 없습니다.");
        }
    }
}
