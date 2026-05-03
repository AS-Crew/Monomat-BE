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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
@Slf4j
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
        User savedUser;
        try {
            // users: 게스트 사용자 기본 정보 저장 (saveAndFlush로 즉시 쿼리 실행하여 중복 예외 캐치)
            savedUser = userRepository.saveAndFlush(User.builder()
                    .username(nickname)
                    .userType(UserType.GUEST)
                    .status(UserStatus.ACTIVE)
                    .lastLoginAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Check-then-Act 구간 사이 다른 요청으로 인해 닉네임 중복이 발생한 경우
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
        }

        // 게스트/회원 공통 식별 정책: userIdentifier는 UUID 사용
        String userIdentifier = UUID.randomUUID().toString();

        // guest_sessions: UUID와 사용자 매핑 저장 (TTL 상수로 교체)
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

        // Redis에는 DB 트랜잭션이 성공적으로 커밋된 이후에만 세션 정보를 저장합니다. (고아 데이터 방지)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storeGuestSessionToRedis(savedUser, userIdentifier, refreshToken.token());
                } catch (Exception e) {
                    log.error("Redis 게스트 세션 저장 실패 - userId: {}", savedUser.getId(), e);
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

    /**
     * 입력 닉네임을 trim 합니다.
     * (null 체크나 길이 검증은 Controller의 @Valid에서 수행되므로 중복 검증 제거)
     */
    private String normalizeNickname(String rawNickname) {
        return rawNickname.trim();
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
    private void storeGuestSessionToRedis(User user, String userIdentifier, String refreshToken) {
        String guestSessionKey = RedisKeys.guestSessionKey(userIdentifier);
        redisTemplate.opsForHash().putAll(guestSessionKey, Map.of(
                "userId", String.valueOf(user.getId()),
                "username", user.getUsername(),
                "userType", user.getUserType().name()
        ));
        redisTemplate.expire(guestSessionKey, GUEST_SESSION_TTL);

        String refreshTokenKey = RedisKeys.refreshTokenKey(userIdentifier);
        redisTemplate.opsForValue().set(refreshTokenKey, refreshToken, jwtTokenProvider.refreshTokenTtl());
    }
}
