package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.GuestSession;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenHashUtils;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 게스트 인증 서비스.
 *
 * 핵심 책임:
 * - 닉네임 기반 게스트 계정 생성
 * - UUID(userIdentifier) 발급
 * - JWT Access/Refresh 발급
 * - DB + Redis 세션 상태를 함께 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestAuthService {

    private static final Duration GUEST_SESSION_TTL = Duration.ofDays(30);

    private static final int MIN_NICKNAME_LENGTH = 2;
    private static final int MAX_NICKNAME_LENGTH = 12;

    private final UserRepository userRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final UserSessionRepository userSessionRepository;
    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserSessionLifecycleService userSessionLifecycleService;
    private final NicknamePolicyValidator nicknamePolicyValidator;

    /**
     * 게스트 로그인 메인 플로우
     * 1) 닉네임 정규화/검증
     * 2) users + guest_sessions 저장
     * 3) Access/Refresh 토큰 발급
     * 4) Redis 세션/리프레시 정보 저장
     */
    @Transactional
    public GuestLoginResponse loginAsGuest(String rawNickname, String ipAddress, String userAgent) {
        String nickname = normalizeNickname(rawNickname);
        nicknamePolicyValidator.validate(nickname);
        validateNicknameAvailability(nickname);

        LocalDateTime now = LocalDateTime.now();

        User savedUser;

        try {
            savedUser = userRepository.saveAndFlush(User.builder()
                    .username(nickname)
                    .userType(UserType.GUEST)
                    .status(UserStatus.ACTIVE)
                    .lastLoginAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_DUPLICATED, e);
        }

        String userIdentifier = UUID.randomUUID().toString();

        guestSessionRepository.save(GuestSession.builder()
                .user(savedUser)
                .guestToken(userIdentifier)
                .expiresAt(now.plus(GUEST_SESSION_TTL))
                .createdAt(now)
                .build());

        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(
                savedUser.getId(),
                savedUser.getUserType(),
                userIdentifier
        );

        TokenWithExpiry refreshToken = jwtTokenProvider.createRefreshToken(
                savedUser.getId(),
                savedUser.getUserType(),
                userIdentifier
        );

        String refreshTokenHash = TokenHashUtils.sha256(refreshToken.token());

        userSessionRepository.save(UserSession.builder()
                .user(savedUser)
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
                savedUser.getId(),
                savedUser.getUserType(),
                userIdentifier,
                now
        );

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storeGuestSessionToRedis(savedUser, userIdentifier, refreshTokenHash);
                } catch (RuntimeException e) {
                    log.error("Redis 게스트 세션 저장 실패 - userId: {}", savedUser.getId(), e);
                    userSessionLifecycleService.markSessionRevokedCompensating(
                            userIdentifier,
                            LocalDateTime.now()
                    );
                }
            }
        });

        return GuestLoginResponse.builder()
                .userId(savedUser.getId())
                .nickname(savedUser.getUsername())
                .userType(savedUser.getUserType())
                .userIdentifier(userIdentifier)
                .accessToken(accessToken.token())
                .accessTokenExpiresAt(accessToken.expiresAt())
                .refreshToken(refreshToken.token())
                .refreshTokenExpiresAt(refreshToken.expiresAt())
                .build();
    }

    private String normalizeNickname(String value) {
        String nickname = normalizeRequiredWithTrim(value, AuthErrorCode.AUTH_NICKNAME_REQUIRED);

        if (nickname.length() < MIN_NICKNAME_LENGTH || nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_INVALID_LENGTH);
        }

        return nickname;
    }

    private String normalizeRequiredWithTrim(String value, AuthErrorCode requiredErrorCode) {
        if (value == null) {
            throw new AuthException(requiredErrorCode);
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            throw new AuthException(requiredErrorCode);
        }

        return normalized;
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

    /**
     * 닉네임 중복 정책:
     * - 등록되어 있는 회원이 선점한 닉네임은 게스트가 사용할 수 없음
     * - 게스트/회원 포함 전체 중복도 차단
     */
    private void validateNicknameAvailability(String nickname) {
        if (userRepository.existsByUsernameAndUserType(nickname, UserType.REGISTERED)) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_DUPLICATED);
        }

        if (userRepository.existsByUsername(nickname)) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_DUPLICATED);
        }
    }

    /**
     * 게스트 세션을 Redis에 저장합니다.
     * - Hash: 게스트 식별/조회용 사용자 정보
     * - String: Refresh Token
     */
    private void storeGuestSessionToRedis(User user, String userIdentifier, String refreshTokenHash) {
        String guestSessionKey = RedisKeys.guestSessionKey(userIdentifier);

        redisTemplate.opsForHash().putAll(guestSessionKey, Map.of(
                RedisKeys.FIELD_GUEST_USER_ID, String.valueOf(user.getId()),
                RedisKeys.FIELD_GUEST_USERNAME, user.getUsername(),
                RedisKeys.FIELD_GUEST_USER_TYPE, user.getUserType().name()
        ));

        redisTemplate.expire(guestSessionKey, GUEST_SESSION_TTL);

        String refreshTokenKey = RedisKeys.refreshTokenKey(userIdentifier);
        redisTemplate.opsForValue().set(refreshTokenKey, refreshTokenHash, jwtTokenProvider.refreshTokenTtl());
        redisTemplate.opsForValue().set(
                RedisKeys.activeSessionKey(userIdentifier),
                "1",
                jwtTokenProvider.refreshTokenTtl()
        );
    }
}