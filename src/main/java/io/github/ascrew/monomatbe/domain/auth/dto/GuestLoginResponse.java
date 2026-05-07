package io.github.ascrew.monomatbe.domain.auth.dto;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import lombok.Builder;

import java.time.Instant;

/**
 * 게스트 로그인 응답 DTO.
 *
 * 프론트는 이 응답으로
 * 1) 사용자 표시 정보
 * 2) WebSocket 연결 식별자(userIdentifier)
 * 3) API 인증용 JWT(access/refresh)
 * 를 한 번에 획득합니다.
 */
@Builder
public record GuestLoginResponse(
        // users PK
        Long userId,
        // 화면 표시용 닉네임
        String nickname,
        // 현재는 GUEST, 향후 REGISTERED와 동일 포맷 사용
        UserType userType,
        // STOMP CONNECT 헤더에 넣는 UUID 식별자
        String userIdentifier,
        // API 인증용 단기 토큰
        String accessToken,
        // Access Token 만료 시각
        Instant accessTokenExpiresAt,
        // Access Token 재발급용 장기 토큰
        String refreshToken,
        // Refresh Token 만료 시각
        Instant refreshTokenExpiresAt
) {
}
