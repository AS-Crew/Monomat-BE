package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;

/**
 * JWT 파싱 후 SecurityContext에 저장되는 인증 주체 객체
 *
 * [설계 이유]
 * 컨트롤러에서 @AuthenticationPrincipal로 주입받아 userId (DB용)와 userIdentifier(Redis/WebSocket용)를
 * 동시에 사용할 수 있도록 한다.
 * 두 식별자를 분리함으로써 DB와 Redis 각각 맞는 식별자를 사용할 수 있다.
 *
 * [권한]
 * userType은 GUEST/REGISTERED 계정 유형이고,
 * role은 USER/ADMIN 인가 권한이다.
 */
public record CustomPrincipal(
        Long userId,
        String userIdentifier,
        UserType userType,
        UserRole role
) {

    /**
     * 기존 테스트 및 일반 사용자 기본 생성 편의를 위한 보조 생성자.
     *
     * role을 명시하지 않는 경우 일반 사용자 권한(USER)으로 처리한다.
     * 관리자 권한이 필요한 테스트나 실제 인증 필터에서는 4개 인자 생성자를 사용해야 한다.
     */
    public CustomPrincipal(
            Long userId,
            String userIdentifier,
            UserType userType
    ) {
        this(userId, userIdentifier, userType, UserRole.USER);
    }
}