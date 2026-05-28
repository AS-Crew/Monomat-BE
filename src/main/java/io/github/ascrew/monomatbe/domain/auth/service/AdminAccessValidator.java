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
 * - principal.userId()가 monomat.admin.user-ids 설정에 포함되어 있어야 한다.
 *
 * [설정 예시]
 * monomat.admin.user-ids=1,2
 *
 * [설계 이유]
 * - userIdentifier는 로그인/세션 식별자 성격이 강하므로 관리자 권한 기준으로 부적절하다.
 * - users.id는 계정 기준 식별자이므로 임시 관리자 allow-list 기준으로 더 안정적이다.
 *
 * [확장 방향]
 * - 추후 users 테이블 또는 별도 role 테이블에 관리자 권한이 추가되면
 *   이 컴포넌트 내부 구현만 ROLE_ADMIN 검증으로 교체한다.
 */
@Component
public class AdminAccessValidator {

    private static final String ERROR_UNAUTHENTICATED =
            "인증 정보가 없습니다.";
    private static final String ERROR_ADMIN_FORBIDDEN =
            "관리자 권한이 필요합니다.";

    private final Set<Long> adminUserIds;

    public AdminAccessValidator(
            @Value("${monomat.admin.user-ids:}") String adminUserIds
    ) {
        this.adminUserIds = parseAdminUserIds(adminUserIds);
    }

    public void validate(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_UNAUTHENTICATED);
        }

        if (!adminUserIds.contains(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_ADMIN_FORBIDDEN);
        }
    }

    private Set<Long> parseAdminUserIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(userId -> !userId.isBlank())
                .map(this::parseUserId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long parseUserId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "관리자 userId 설정은 숫자만 사용할 수 있습니다. value=" + value,
                    e
            );
        }
    }
}