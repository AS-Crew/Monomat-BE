package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminAccessValidatorTest {

    @Test
    @DisplayName("설정된 관리자 userIdentifier이면 접근을 허용한다")
    void validateAdminUserIdentifier() {
        AdminAccessValidator validator = new AdminAccessValidator("admin-uuid");

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "admin-uuid",
                UserType.REGISTERED
        );

        assertDoesNotThrow(() -> validator.validate(principal));
    }

    @Test
    @DisplayName("설정되지 않은 userIdentifier이면 403으로 차단한다")
    void rejectNonAdminUserIdentifier() {
        AdminAccessValidator validator = new AdminAccessValidator("admin-uuid");

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "normal-user-uuid",
                UserType.REGISTERED
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> validator.validate(principal)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    @DisplayName("인증 정보가 없으면 401로 차단한다")
    void rejectUnauthenticatedPrincipal() {
        AdminAccessValidator validator = new AdminAccessValidator("admin-uuid");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> validator.validate(null)
        );

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    @DisplayName("관리자 식별자는 콤마 구분 목록으로 여러 개 설정할 수 있다")
    void validateMultipleAdminUserIdentifiers() {
        AdminAccessValidator validator = new AdminAccessValidator("admin-uuid-1, admin-uuid-2");

        CustomPrincipal principal = new CustomPrincipal(
                1L,
                "admin-uuid-2",
                UserType.REGISTERED
        );

        assertDoesNotThrow(() -> validator.validate(principal));
    }
}