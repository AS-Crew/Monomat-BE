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

    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterResponse register(String rawLoginId, String rawPassword, String rawNickname) {
        String loginId = normalizeRequired(rawLoginId, "로그인 ID");
        String password = normalizeRequired(rawPassword, "비밀번호");
        String nickname = normalizeRequired(rawNickname, "닉네임");

        validateDuplicate(loginId, nickname);

        User savedUser;
        try {
            savedUser = userRepository.saveAndFlush(User.builder()
                    .username(nickname)
                    .userType(UserType.REGISTERED)
                    .status(UserStatus.ACTIVE)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
        }

        try {
            userCredentialRepository.saveAndFlush(UserCredential.builder()
                    .user(savedUser)
                    .loginId(loginId)
                    .passwordHash(passwordEncoder.encode(password))
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 로그인 ID입니다.");
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 로그인 ID입니다.");
        }
        if (userRepository.existsByUsername(nickname)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "는 비어 있을 수 없습니다.");
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "는 비어 있을 수 없습니다.");
        }
        return normalized;
    }
}
