package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 API 접근 검증 컴포넌트
 *
 * [현재 정책]
 * - 프로젝트에 ROLE_ADMIN 권한 체계가 아직 없으므로 설정 기반 allow-list로 관리자 접근을 제한한다.
 * - principal.userIdentifier()가 monomat.admin.user-identifiers 설정에 포함되어 있어야 한다.
 *
 * [설정 예시]
 * monomat.admin.user-identifiers=uuid-1,uuid-2
 *
 * [확장 방향]
 * - 추후 users 테이블 또는 별도 role 테이블에 관리자 권한이 추가되면 이 컴포넌트 내부 구현만 ROLE_ADMIN 검증으로 교체한다.
 */
@Component
public class AdminAccessValidator {

    private static final String ERROR_UNAUTHENTICATED =
            "인증 정보가 없습니다.";
    private static final String ERROR_ADMIN_FORBIDDEN =
            "관리자 권한이 필요합니다.";

    private final Set<String> adminUserIdentifiers;

    public AdminAccessValidator(
            @Value("${monomat.admin.user-identifiers:}") String adminUserIdentifiers
    ) {
        this.adminUserIdentifiers = parseAdminUserIdentifiers(adminUserIdentifiers);
    }

    public void validate(CustomPrincipal principal) {
        if (principal == null || principal.userIdentifier() == null || principal.userIdentifier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHENTICATED);
        }

        if (!adminUserIdentifiers.contains(principal.userIdentifier())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_ADMIN_FORBIDDEN);
        }
    }

    private Set<String> parseAdminUserIdentifiers(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(identifier -> !identifier.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}