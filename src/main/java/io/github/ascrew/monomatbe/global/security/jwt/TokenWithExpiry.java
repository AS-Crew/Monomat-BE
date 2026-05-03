package io.github.ascrew.monomatbe.global.security.jwt;

import java.time.Instant;

/**
 * JWT 문자열과 만료시각을 함께 전달하기 위한 값 객체.
 *
 * 토큰만 반환하면 만료시각 계산을 호출부가 다시 해야 하므로
 * 발급 시점에서 계산된 expiresAt을 함께 전달해 응답/캐시 처리에 재사용합니다.
 */
public record TokenWithExpiry(
        String token,
        Instant expiresAt
) {
}
