package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.AdminUserRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAccessValidatorTest {

    private AdminUserRepository adminUserRepository;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        adminUserRepository = mock(AdminUserRepository.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("admin_users 테이블에 등록된 REGISTERED userId이면 true를 반환한다")
    void isAdminWithDatabaseAdminUserId() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                ""
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(1L, UserType.REGISTERED))
                .thenReturn(true);

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(1L, UserType.REGISTERED);

        assertEquals(0.0, meterRegistry.counter("monomat.admin.access.db.failure").count());
    }

    @Test
    @DisplayName("DB에 관리자 권한이 없고 fallback에도 없으면 false를 반환한다")
    void isAdminRejectsNonAdminUserId() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                ""
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(2L, UserType.REGISTERED))
                .thenReturn(false);

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(2L, "normal-user-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(2L, UserType.REGISTERED);
    }

    @Test
    @DisplayName("DB에는 없지만 fallback allow-list에 있으면 접근을 허용하고 not_registered metric을 증가시킨다")
    void isAdminWithFallbackWhenNotRegisteredInDatabaseIncrementsMetrics() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(1L, UserType.REGISTERED))
                .thenReturn(false);

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(1L, UserType.REGISTERED);

        assertEquals(
                1.0,
                meterRegistry.counter(
                        "monomat.admin.access.fallback.allowed",
                        "reason",
                        "not_registered"
                ).count()
        );
    }

    @Test
    @DisplayName("DB 조회 실패 시 fallback allow-list에 있으면 접근을 허용하고 metric을 증가시킨다")
    void isAdminWithFallbackWhenDatabaseFailsIncrementsMetrics() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(1L, UserType.REGISTERED))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(1L, UserType.REGISTERED);

        assertEquals(
                1.0,
                meterRegistry.counter("monomat.admin.access.db.failure").count()
        );
        assertEquals(
                1.0,
                meterRegistry.counter(
                        "monomat.admin.access.fallback.allowed",
                        "reason",
                        "db_failure"
                ).count()
        );
    }

    @Test
    @DisplayName("DB 조회 실패 시 fallback allow-list에도 없으면 false를 반환하고 DB 실패 metric만 증가시킨다")
    void rejectWhenDatabaseFailsAndFallbackDoesNotContainUserId() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                ""
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(1L, UserType.REGISTERED))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(1L, UserType.REGISTERED);

        assertEquals(
                1.0,
                meterRegistry.counter("monomat.admin.access.db.failure").count()
        );
        assertEquals(
                0.0,
                meterRegistry.counter(
                        "monomat.admin.access.fallback.allowed",
                        "reason",
                        "db_failure"
                ).count()
        );
    }

    @Test
    @DisplayName("fallback 설정에 숫자가 아닌 값이 있으면 애플리케이션 시작을 막는다")
    void rejectInvalidFallbackAdminUserIdSetting() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AdminAccessValidator(adminUserRepository, meterRegistry, "1, admin, 2")
        );

        assertTrue(exception.getMessage().contains("관리자 fallback userId 설정값에 숫자가 아닌 값"));
        assertTrue(exception.getMessage().contains("admin"));
    }

    @Test
    @DisplayName("fallback 설정에 빈 토큰이 섞여 있어도 빈 토큰은 무시하고 정상 값만 반영한다")
    void ignoreBlankTokensInFallbackAdminUserIdSetting() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1, , 2,   "
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(1L, UserType.REGISTERED))
                .thenReturn(false);

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(1L, UserType.REGISTERED);

        assertEquals(
                1.0,
                meterRegistry.counter(
                        "monomat.admin.access.fallback.allowed",
                        "reason",
                        "not_registered"
                ).count()
        );
    }

    @Test
    @DisplayName("fallback userId는 콤마 구분 목록으로 여러 개 설정할 수 있다")
    void isAdminWithMultipleFallbackAdminUserIds() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1, 2"
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(2L, UserType.REGISTERED))
                .thenReturn(false);

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(2L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(2L, UserType.REGISTERED);
    }

    @Test
    @DisplayName("fallback 설정이 비어 있고 DB에도 없으면 false를 반환한다")
    void rejectWhenFallbackAdminUserIdSettingIsEmpty() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                ""
        );

        when(adminUserRepository.existsByUserIdAndUserUserType(1L, UserType.REGISTERED))
                .thenReturn(false);

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository).existsByUserIdAndUserUserType(1L, UserType.REGISTERED);
    }

    @Test
    @DisplayName("Authentication이 null이면 false를 반환하고 DB를 조회하지 않는다")
    void isAdminRejectsNullAuthentication() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        assertFalse(validator.isAdmin(null));
        verify(adminUserRepository, never()).existsByUserIdAndUserUserType(any(), any());
    }

    @Test
    @DisplayName("인증되지 않은 Authentication이면 false를 반환하고 DB를 조회하지 않는다")
    void isAdminRejectsUnauthenticatedAuthentication() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );
        authentication.setAuthenticated(false);

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository, never()).existsByUserIdAndUserUserType(any(), any());
    }

    @Test
    @DisplayName("principal이 CustomPrincipal이 아니면 false를 반환하고 DB를 조회하지 않는다")
    void isAdminRejectsUnsupportedPrincipal() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        TestingAuthenticationToken authentication = authenticatedToken("anonymousUser");

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository, never()).existsByUserIdAndUserUserType(any(), any());
    }

    @Test
    @DisplayName("principal의 userId가 null이면 false를 반환하고 DB를 조회하지 않는다")
    void isAdminRejectsNullUserId() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(null, "any-session-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository, never()).existsByUserIdAndUserUserType(any(), any());
    }

    @Test
    @DisplayName("principal의 userType이 GUEST이면 admin_users 등록 여부를 조회하지 않고 false를 반환한다")
    void isAdminRejectsGuestUserType() {
        AdminAccessValidator validator = new AdminAccessValidator(
                adminUserRepository,
                meterRegistry,
                "1"
        );

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "guest-session-identifier", UserType.GUEST)
        );

        assertFalse(validator.isAdmin(authentication));
        verify(adminUserRepository, never()).existsByUserIdAndUserUserType(any(), any());
    }

    private TestingAuthenticationToken authenticatedToken(Object principal) {
        return new TestingAuthenticationToken(
                principal,
                null,
                "ROLE_USER"
        );
    }
}