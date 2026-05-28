package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 API 접근 검증 컴포넌트
 *
 * [현재 정책]
 * - 프로젝트에 ROLE_ADMIN 권한 체계가 아직 없으므로 설정 기반 allow-list로 관리자 접근을 제한한다.
 * - Authentication principal의 CustomPrincipal.userId()가 monomat.admin.user-ids 설정에 포함되어 있어야 한다.
 *
 * [설정 예시]
 * monomat.admin.user-ids=1,2
 *
 * [설계 이유]
 * - userIdentifier는 로그인/세션 식별자 성격이 강하므로 관리자 권한 기준으로 부적절하다.
 * - users.id는 계정 기준 식별자이므로 임시 관리자 allow-list 기준으로 더 안정적이다.
 *
 * [운영 안전성]
 * - 관리자 allow-list는 부가 기능 설정이다.
 * - 환경변수 오타 때문에 전체 애플리케이션이 부팅 실패하면 안 된다.
 * - 잘못된 값은 warn 로그만 남기고 무시한다.
 *
 * [인가 정책]
 * - Controller에서 수동으로 validate()를 호출하지 않는다.
 * - @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")로 중앙집중화한다.
 * - 새 관리자 API가 추가되어도 동일한 어노테이션만 붙이면 인가 정책을 일관되게 적용할 수 있다.
 *
 * [확장 방향]
 * - 추후 users 테이블 또는 별도 role 테이블에 관리자 권한이 추가되면
 *   이 컴포넌트 내부 구현만 ROLE_ADMIN 검증으로 교체한다.
 */
@Slf4j
@Component("adminAccessValidator")
public class AdminAccessValidator {

    private final Set<Long> adminUserIds;

    public AdminAccessValidator(
            @Value("${monomat.admin.user-ids:}") String adminUserIds
    ) {
        this.adminUserIds = parseAdminUserIds(adminUserIds);
    }

    /**
     * Spring Method Security SpEL에서 호출하는 관리자 여부 판단 메서드
     *
     * 사용 예:
     * @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")
     *
     * @param authentication Spring Security 인증 객체
     * @return 관리자 접근 가능 여부
     */
    public boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof CustomPrincipal customPrincipal)) {
            return false;
        }

        Long userId = customPrincipal.userId();

        return userId != null && adminUserIds.contains(userId);
    }

    private Set<Long> parseAdminUserIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(userId -> !userId.isBlank())
                .map(this::parseUserIdOrNull)
                .filter(userId -> userId != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 관리자 userId 설정값을 Long으로 변환한다.
     *
     * [정책]
     * - 숫자가 아닌 값은 전체 서버 부팅을 막지 않는다.
     * - 잘못된 값은 warn 로그만 남기고 무시한다.
     * - 결과적으로 잘못 설정된 관리자 ID는 관리자 API에 접근할 수 없다.
     */
    private Long parseUserIdOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn(
                    "관리자 userId 설정값이 숫자가 아니어서 무시합니다. value={}",
                    value
            );
            return null;
        }
    }
}