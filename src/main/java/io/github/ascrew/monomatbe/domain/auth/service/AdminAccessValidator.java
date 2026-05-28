package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.AdminUserRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component("adminAccessValidator")
public class AdminAccessValidator {

    private final AdminUserRepository adminUserRepository;
    private final Set<Long> fallbackAdminUserIds;

    public AdminAccessValidator(
            AdminUserRepository adminUserRepository,
            @Value("${monomat.admin.user-ids:}") String fallbackAdminUserIds
    ) {
        this.adminUserRepository = adminUserRepository;
        this.fallbackAdminUserIds = parseFallbackAdminUserIds(fallbackAdminUserIds);

        if (this.fallbackAdminUserIds.isEmpty()) {
            log.warn(
                    "관리자 fallback userId allow-list가 비어 있습니다. " +
                            "admin_users 테이블에 등록된 REGISTERED 사용자만 관리자 API에 접근할 수 있습니다. " +
                            "긴급 fallback이 필요하면 MONOMAT_ADMIN_USER_IDS 또는 monomat.admin.user-ids를 설정하세요."
            );
        }
    }

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

        if (isAdminByDatabase(userId)) {
            return true;
        }

        return isAdminByFallback(userId);
    }

    private boolean isAdminByDatabase(Long userId) {
        try {
            return adminUserRepository.existsByUserIdAndUserUserType(
                    userId,
                    UserType.REGISTERED
            );
        } catch (DataAccessException e) {
            log.error(
                    "관리자 권한 DB 조회 실패 - fallback allow-list 확인으로 대체합니다. userId: {}",
                    userId,
                    e
            );
            return false;
        }
    }

    private boolean isAdminByFallback(Long userId) {
        boolean allowed = fallbackAdminUserIds.contains(userId);

        if (allowed) {
            log.warn(
                    "관리자 fallback allow-list로 접근을 허용합니다. userId: {}",
                    userId
            );
        }

        return allowed;
    }

    private Set<Long> parseFallbackAdminUserIds(String value) {
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

    private Long parseUserIdOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn(
                    "관리자 fallback userId 설정값이 숫자가 아니어서 무시합니다. value={}",
                    value
            );
            return null;
        }
    }
}