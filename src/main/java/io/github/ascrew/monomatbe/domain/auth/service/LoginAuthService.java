package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserCredential;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthLoginFailureException;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAuthService {

    private static final int LOCK_THRESHOLD = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserCredentialRepository userCredentialRepository;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserSessionLifecycleService userSessionLifecycleService;

    @Value("${auth.redis.refresh-store-enabled:true}")
    private boolean refreshStoreEnabled;

    /**
     * 자체 로그인
     *
     * [트랜잭션 정책]
     * 비밀번호 불일치 시 failedLoginCount 증가와 lockedUntil 설정은 반드시 커밋되어야 한다.
     * 따라서 해당 상태 변경 이후에는 AuthLoginFailureException을 던지고,
     * 이 예외만 noRollbackFor 대상으로 제한한다.
     *
     * [주의]
     * AuthException 전체를 noRollbackFor로 지정하지 않는다.
     * AuthException은 계정 잠금, 인증 정보 없음, refresh token 오류 등 다양한 인증 예외를 포괄하므로,
     * 향후 login() 내부에서 DB write 이후 다른 AuthException이 추가될 경우 의도하지 않은 부분 커밋이 발생할 수 있다.
     *
     * [호환성 정책]
     * 회원가입 정책과 로그인 정책은 분리한다.
     * 회원가입에서는 신규 loginId 포맷을 강제할 수 있지만,
     * 로그인에서는 과거에 생성된 계정의 호환성을 위해 loginId 포맷 검증을 하지 않는다.
     *
     * 따라서 loginId는 null/blank만 차단하고,
     * DB 조회 실패 또는 비밀번호 불일치는 AUTH_INVALID_CREDENTIALS로 통합 처리한다.
     */
    @Transactional(noRollbackFor = AuthLoginFailureException.class)
    public LoginResponse login(String rawLoginId, String rawPassword, boolean force, String ipAddress, String userAgent) {
        String loginId = normalizeLoginId(rawLoginId);
        String password = normalizePassword(rawPassword);

        UserCredential credential = userCredentialRepository.findByLoginId(loginId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS));

        LocalDateTime now = LocalDateTime.now();

        if (credential.isLockedAt(now)) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            credential.increaseFailedLoginCount();

            if (credential.getFailedLoginCount() >= LOCK_THRESHOLD) {
                credential.lockUntil(now.plus(LOCK_DURATION));
            }

            throw new AuthLoginFailureException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        credential.resetFailedLoginState();

        User user = credential.getUser();
        user.updateLastLoginAt(now);

        UserType userType = user.getUserType();

        enforceConcurrentLoginPolicy(user.getId(), userType, force, now);

        String userIdentifier = UUID.randomUUID().toString();

        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(
                user.getId(),
                userType,
                user.getRole(),
                userIdentifier
        );

        TokenWithExpiry refreshToken = jwtTokenProvider.createRefreshToken(
                user.getId(),
                userType,
                userIdentifier
        );

        String refreshTokenHash = TokenHashUtils.sha256(refreshToken.token());

        userSessionRepository.save(UserSession.builder()
                .user(user)
                .sessionId(userIdentifier)
                .sessionToken(refreshTokenHash)
                .ipAddress(normalizeOptionalLength(ipAddress, 45))
                .userAgent(normalizeOptionalLength(userAgent, 500))
                .expiresAt(LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneId.systemDefault()))
                .createdAt(now)
                .updatedAt(now)
                .status(UserSessionStatus.ACTIVE)
                .build());

        userSessionLifecycleService.enforceActiveSessionLimit(
                user.getId(),
                userType,
                userIdentifier,
                now
        );

        if (refreshStoreEnabled) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    String refreshTokenKey = RedisKeys.refreshTokenKey(userIdentifier);

                    try {
                        redisTemplate.opsForValue().set(
                                refreshTokenKey,
                                refreshTokenHash,
                                jwtTokenProvider.refreshTokenTtl()
                        );
                        redisTemplate.opsForValue().set(
                                RedisKeys.activeSessionKey(userIdentifier),
                                "1",
                                jwtTokenProvider.refreshTokenTtl()
                        );
                    } catch (RuntimeException e) {
                        log.error("로그인 후 Redis 세션 저장 실패 - sessionId: {}", userIdentifier, e);
                        userSessionLifecycleService.markSessionRevokedCompensating(
                                userIdentifier,
                                LocalDateTime.now()
                        );
                    }
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

    /**
     * 회원 중복 로그인 정책을 적용한다. (#204)
     *
     * [정책: 기존 세션 우선(block-new-login)]
     * 회원(REGISTERED)이 이미 만료되지 않은 활성 세션을 보유하고 있으면 신규 로그인을 거부한다.
     * 단, force=true이면 기존 활성 세션을 모두 revoke한 뒤 로그인을 진행한다.(lockout 방지용 강제 로그인)
     *
     * [게스트 예외]
     * 게스트(GUEST)는 다중 세션을 허용하므로(max-active-per-user-guest) 이 정책에서 제외하고,
     * 기존 newest-wins 동작(enforceActiveSessionLimit)을 그대로 유지한다.
     *
     * [만료 세션 처리]
     * status는 ACTIVE이지만 expiresAt이 지난(아직 정리 스케줄러가 처리하지 않은) 세션은
     * 차단 대상에서 제외하여, 비정상 종료 후 만료된 세션이 재로그인을 막는 lockout을 완화한다.
     */
    private void enforceConcurrentLoginPolicy(Long userId, UserType userType, boolean force, LocalDateTime now) {
        if (userType != UserType.REGISTERED) {
            return;
        }

        boolean hasLiveSession = userSessionRepository
                .findByUser_IdAndStatusOrderByCreatedAtAsc(userId, UserSessionStatus.ACTIVE)
                .stream()
                .anyMatch(session -> session.getExpiresAt() != null && session.getExpiresAt().isAfter(now));

        if (!hasLiveSession) {
            return;
        }

        if (!force) {
            throw new AuthException(AuthErrorCode.AUTH_CONCURRENT_LOGIN_REJECTED);
        }

        userSessionLifecycleService.revokeAllActiveSessions(userId, now);
    }

    private String normalizeLoginId(String value) {
        return normalizeRequiredWithTrim(value, AuthErrorCode.AUTH_LOGIN_ID_REQUIRED);
    }

    /**
     * 로그인 비밀번호는 null/blank만 차단한다.
     *
     * [이유]
     * 로그인 시점에 비밀번호 길이/포맷을 과하게 검증하면
     * 기존 계정 호환성 또는 정책 변경 전 계정의 로그인을 막을 수 있다.
     * 실제 인증 실패는 passwordEncoder.matches() 결과를 통해 AUTH_INVALID_CREDENTIALS로 통합 처리한다.
     */
    private String normalizePassword(String value) {
        return normalizeRequired(value, AuthErrorCode.AUTH_PASSWORD_REQUIRED);
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