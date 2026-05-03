package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.GuestSession;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.JwtTokenProvider;
import io.github.ascrew.monomatbe.global.security.jwt.TokenWithExpiry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
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
@Service
@RequiredArgsConstructor
public class GuestAuthService {

    private static final Duration GUEST_SESSION_TTL = Duration.ofDays(30);

    private final UserRepository userRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 게스트 로그인 메인 플로우
     * 1) 닉네임 정규화/검증
     * 2) users + guest_sessions 저장
     * 3) Access/Refresh 토큰 발급
     * 4) Redis 세션/리프레시 정보 저장
     */
    @Transactional
    public GuestLoginResponse loginAsGuest(String rawNickname) {
        String nickname = normalizeNickname(rawNickname);
        validateNicknameAvailability(nickname);

        LocalDateTime now = LocalDateTime.now();
        // users: 게스트 사용자 기본 정보 저장
        User savedUser = userRepository.save(User.builder()
                .username(nickname)
                .userType(UserType.GUEST)
                .status(UserStatus.ACTIVE)
                .lastLoginAt(now)
                .build());

        // 게스트/회원 공통 식별 정책: userIdentifier는 UUID 사용
        String guestToken = UUID.randomUUID().toString();

        // guest_sessions: UUID와 사용자 매핑 저장 (30일 만료 기준)
        guestSessionRepository.save(GuestSession.builder()
                .user(savedUser)
                .guestToken(guestToken)
                .expiresAt(now.plusDays(30))
                .createdAt(now)
                .build());

        TokenWithExpiry accessToken = jwtTokenProvider.createAccessToken(
                savedUser.getId(),
                savedUser.getUserType(),
                guestToken
        );
        TokenWithExpiry refreshToken = jwtTokenProvider.createRefreshToken(
                savedUser.getId(),
                savedUser.getUserType(),
                guestToken
        );

        // Redis에는 빠른 조회/유효성 확인에 필요한 최소 세션 정보를 저장
        storeGuestSessionToRedis(savedUser, guestToken, refreshToken.token());

        return GuestLoginResponse.builder()
                .userId(savedUser.getId())
                .nickname(savedUser.getUsername())
                .userType(savedUser.getUserType())
                .userIdentifier(guestToken)
                .accessToken(accessToken.token())
                .accessTokenExpiresAt(accessToken.expiresAt())
                .refreshToken(refreshToken.token())
                .refreshTokenExpiresAt(refreshToken.expiresAt())
                .build();
    }

    /**
     * 입력 닉네임을 trim 후 유효성 검증합니다.
     */
    private String normalizeNickname(String rawNickname) {
        if (rawNickname == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 비어 있을 수 없습니다.");
        }
        String normalized = rawNickname.trim();
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 비어 있을 수 없습니다.");
        }
        if (normalized.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 50자를 초과할 수 없습니다.");
        }
        return normalized;
    }

    /**
     * 닉네임 중복 정책:
     * - 등록되어 있는 회원이 선점한 닉네임은 게스트가 사용할 수 없음
     * - 게스트/회원 포함 전체 중복도 차단
     */
    private void validateNicknameAvailability(String nickname) {
        if (userRepository.existsByUsernameAndUserType(nickname, UserType.REGISTERED)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "다른 회원이 이미 사용 중인 닉네임입니다."
            );
        }

        if (userRepository.existsByUsername(nickname)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
        }
    }

    /**
     * 게스트 세션을 Redis에 저장합니다.
     * - Hash: 게스트 식별/조회용 사용자 정보
     * - String: Refresh Token
     */
    private void storeGuestSessionToRedis(User user, String guestToken, String refreshToken) {
        String guestSessionKey = RedisKeys.guestSessionKey(guestToken);
        redisTemplate.opsForHash().putAll(guestSessionKey, Map.of(
                "userId", String.valueOf(user.getId()),
                "username", user.getUsername(),
                "userType", user.getUserType().name()
        ));
        redisTemplate.expire(guestSessionKey, GUEST_SESSION_TTL);

        String refreshTokenKey = RedisKeys.refreshTokenKey(guestToken);
        redisTemplate.opsForValue().set(refreshTokenKey, refreshToken, jwtTokenProvider.refreshTokenTtl());
    }
}
