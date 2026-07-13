package io.github.ascrew.monomatbe.global.security.jwt;

import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;

/**
 * 검증이 완료된 Access Token의 인증 정보를 표현한다.
 * REST와 STOMP가 동일한 JWT Claim 해석 결과를 사용하도록 인증 결과를 별도 객체로 관리한다.
 */
public record AccessTokenAuthentication(
        Long userId,
        String userIdentifier,
        UserType userType,
        UserRole userRole
) {

    /**
     * REST SecurityContext에서 사용하는 Principal로 변환한다.
     */
    public CustomPrincipal toPrincipal() {
        return new CustomPrincipal(
                userId,
                userIdentifier,
                userType,
                userRole
        );
    }
}