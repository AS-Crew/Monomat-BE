package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginAuthService {

    private static final int LOCK_THRESHOLD = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final String ERR_INVALID_CREDENTIALS = "로그인 ID 또는 비밀번호가 올바르지 않습니다.";
    private static final String ERR_ACCOUNT_LOCKED = "로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요.";

    private final UserCredentialRepository userCredentialRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    @Value("${auth.redis.refresh-store-enabled:true}")
    private boolean refreshStoreEnabled;

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public LoginResponse login(String rawLoginId, String rawPassword, String ipAddress, String userAgent) {
        String loginId = normalizeRequiredWithTrim(rawLoginId, "로그인 ID");
        validateNoWhitespace(loginId, "로그인 ID");

        String password = validateNoWhitespace(rawPassword, "비밀번호");

        UserCredential credential = userCredentialRepository.findByLoginId(loginId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_CREDENTIALS));

        LocalDateTime now = LocalDateTime.now();
        if (credential.isLockedAt(now)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, ERR_ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            credential.increaseFailedLoginCount();
            if (credential.getFailedLoginCount() >= LOCK_THRESHOLD) {
                credential.lockUntil(now.plus(LOCK_DURATION));
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERR_INVALID_CREDENTIALS);
        }

        credential.resetFailedLoginState();
        User user = credential.getUser();
        user.updateLastLoginAt(now);

        String userIdentifier = UUID.randomUUID().toString();
        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                user.getUserType(),
                userIdentifier
        );
        TokenWithExpiry refreshToken = jwtTokenProvider.createRefreshToken(
                user.getId(),
                user.getUserType(),
                userIdentifier
        );

        userSessionRepository.save(UserSession.builder()
                .user(user)
                .sessionToken(refreshToken.token())
                .ipAddress(normalizeOptionalLength(ipAddress, 45))
                .userAgent(normalizeOptionalLength(userAgent, 500))
                .expiresAt(LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneId.systemDefault()))
                .createdAt(now)
                .build());

        if (refreshStoreEnabled) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    String refreshTokenKey = RedisKeys.refreshTokenKey(userIdentifier);
                    redisTemplate.opsForValue().set(refreshTokenKey, refreshToken.token(), jwtTokenProvider.refreshTokenTtl());
                }
            });
        }

        return LoginResponse.builder()
                .userId(user.getId())
                .loginId(credential.getLoginId())
                .nickname(user.getUsername())
                .userType(user.getUserType())
                .userIdentifier(userIdentifier)
                .accessToken(accessToken.token())
                .accessTokenExpiresAt(accessToken.expiresAt())
                .refreshToken(refreshToken.token())
                .refreshTokenExpiresAt(refreshToken.expiresAt())
                .build();
    }

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

    private String normalizeOptionalLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > maxLength
                ? normalized.substring(0, maxLength)
                : normalized;
    }
}
