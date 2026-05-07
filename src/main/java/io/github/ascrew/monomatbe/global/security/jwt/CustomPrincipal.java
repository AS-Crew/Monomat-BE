package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;

/**
 * JWT 파싱 후 SecurityContext에 저장되는 인증 주체 객체
 *
 * [설계 이유]
 * 컨트롤러에서 @AuthenticationPrincipal로 주입받아 userId (DB용)와 userIdentifier(Redis/WebSocket용)를 동시에 사용할 수 있도록한다.
 * 두 식별자를 분리함으로써 DB와 Redis 각각 맞는 식별자를 사용할 수 있다.
 */
public record CustomPrincipal(
        Long userId,              // DB users.id (FK 참조용)
        String userIdentifier,    // UUID (Redis/WebSocket 식별자)
        UserType userType         // GUEST | REGISTERED
) {
}