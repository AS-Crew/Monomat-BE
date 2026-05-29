package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.AdminUserRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 API 접근 검증 컴포넌트
 *
 * [현재 정책]
 * - ROLE_ADMIN 권한 체계가 아직 없으므로 admin_users 테이블 기반으로 관리자 접근을 제한한다.
 * - admin_users.user_id에 등록된 REGISTERED 사용자만 관리자 API에 접근할 수 있다.
 *
 * [fallback 정책]
 * - MONOMAT_ADMIN_USER_IDS는 운영 주 권한 저장소가 아니다.
 * - DB 장애 또는 local/dev 긴급 접근을 위한 비상 fallback이다.
 * - fallback으로 접근을 허용할 경우 metric과 warn 로그를 남긴다.
 *
 * [보안 설정 검증]
 * - fallback 설정값에 숫자가 아닌 값이 있으면 애플리케이션 시작을 막는다.
 * - 잘못된 관리자 설정이 조용히 무시되면 운영자가 런타임에서야 권한 누락을 발견할 수 있기 때문이다.
 */
@Slf4j
@Component("adminAccessValidator")
public class AdminAccessValidator {

    private static final String ADMIN_DB_FAILURE_METRIC_NAME =
            "monomat.admin.access.db.failure";

    private static final String ADMIN_FALLBACK_ALLOWED_METRIC_NAME =
            "monomat.admin.access.fallback.allowed";

    private final AdminUserRepository adminUserRepository;
    private final MeterRegistry meterRegistry;
    private final Set<Long> fallbackAdminUserIds;

    public AdminAccessValidator(
            AdminUserRepository adminUserRepository,
            MeterRegistry meterRegistry,
            @Value("${monomat.admin.user-ids:}") String fallbackAdminUserIds
    ) {
        this.adminUserRepository = adminUserRepository;
        this.meterRegistry = meterRegistry;
        this.fallbackAdminUserIds = parseFallbackAdminUserIds(fallbackAdminUserIds);

        if (this.fallbackAdminUserIds.isEmpty()) {
            log.warn(
                    "관리자 fallback userId allow-list가 비어 있습니다. " +
                            "admin_users 테이블에 등록된 REGISTERED 사용자만 관리자 API에 접근할 수 있습니다. " +
                            "긴급 fallback이 필요하면 MONOMAT_ADMIN_USER_IDS 또는 monomat.admin.user-ids를 설정하세요."
            );
        }
    }

    /**
     * Spring Method Security SpEL에서 호출하는 관리자 여부 판단 메서드.
     *
     * 사용 예:
     * @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")
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
        UserType userType = customPrincipal.userType();

        if (userId == null || userType != UserType.REGISTERED) {
            return false;
        }

        AdminDatabaseCheckResult databaseCheckResult = checkAdminByDatabase(userId);

        if (databaseCheckResult.admin()) {
            return true;
        }

        if (databaseCheckResult.failed()) {
            return isAdminByFallback(userId, "db_failure");
        }

        return isAdminByFallback(userId, "not_registered");
    }

    private AdminDatabaseCheckResult checkAdminByDatabase(Long userId) {
        try {
            boolean exists = adminUserRepository.existsByUserIdAndUserUserType(
                    userId,
                    UserType.REGISTERED
            );

            return AdminDatabaseCheckResult.success(exists);
        } catch (DataAccessException e) {
            incrementAdminDatabaseFailureMetric();

            log.error(
                    "관리자 권한 DB 조회 실패 - fallback allow-list 확인으로 대체합니다. userId: {}",
                    userId,
                    e
            );

            return AdminDatabaseCheckResult.failure();
        }
    }

    private boolean isAdminByFallback(Long userId, String reason) {
        boolean allowed = fallbackAdminUserIds.contains(userId);

        if (allowed) {
            incrementFallbackAllowedMetric(reason);

            log.warn(
                    "관리자 fallback allow-list로 접근을 허용합니다. userId: {}, reason: {}",
                    userId,
                    reason
            );
        }

        return allowed;
    }

    private void incrementAdminDatabaseFailureMetric() {
        Counter.builder(ADMIN_DB_FAILURE_METRIC_NAME)
                .description("Number of admin access database check failures")
                .register(meterRegistry)
                .increment();
    }

    private void incrementFallbackAllowedMetric(String reason) {
        Counter.builder(ADMIN_FALLBACK_ALLOWED_METRIC_NAME)
                .description("Number of admin accesses allowed by fallback allow-list")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private Set<Long> parseFallbackAdminUserIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        List<String> invalidTokens = new ArrayList<>();

        Set<Long> parsedUserIds = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(userId -> !userId.isBlank())
                .map(userId -> parseUserIdOrNull(userId, invalidTokens))
                .filter(userId -> userId != null)
                .collect(Collectors.toUnmodifiableSet());

        if (!invalidTokens.isEmpty()) {
            throw new IllegalStateException(
                    "관리자 fallback userId 설정값에 숫자가 아닌 값이 포함되어 있습니다. " +
                            "MONOMAT_ADMIN_USER_IDS 또는 monomat.admin.user-ids를 확인하세요. " +
                            "invalidValues=" + invalidTokens
            );
        }

        return parsedUserIds;
    }

    private Long parseUserIdOrNull(String value, List<String> invalidTokens) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            invalidTokens.add(value);
            return null;
        }
    }

    private record AdminDatabaseCheckResult(
            boolean admin,
            boolean failed
    ) {

        private static AdminDatabaseCheckResult success(boolean admin) {
            return new AdminDatabaseCheckResult(admin, false);
        }

        private static AdminDatabaseCheckResult failure() {
            return new AdminDatabaseCheckResult(false, true);
        }
    }
}