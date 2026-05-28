package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAccessValidatorTest {

    @Test
    @DisplayName("설정된 관리자 userId이면 true를 반환한다")
    void isAdminWithAdminUserId() {
        AdminAccessValidator validator = new AdminAccessValidator("1");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("설정되지 않은 userId이면 false를 반환한다")
    void isAdminRejectsNonAdminUserId() {
        AdminAccessValidator validator = new AdminAccessValidator("1");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(2L, "normal-user-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("Authentication이 null이면 false를 반환한다")
    void isAdminRejectsNullAuthentication() {
        AdminAccessValidator validator = new AdminAccessValidator("1");

        assertFalse(validator.isAdmin(null));
    }

    @Test
    @DisplayName("인증되지 않은 Authentication이면 false를 반환한다")
    void isAdminRejectsUnauthenticatedAuthentication() {
        AdminAccessValidator validator = new AdminAccessValidator("1");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );
        authentication.setAuthenticated(false);

        assertFalse(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("principal이 CustomPrincipal이 아니면 false를 반환한다")
    void isAdminRejectsUnsupportedPrincipal() {
        AdminAccessValidator validator = new AdminAccessValidator("1");

        TestingAuthenticationToken authentication = authenticatedToken("anonymousUser");

        assertFalse(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("principal의 userId가 null이면 false를 반환한다")
    void isAdminRejectsNullUserId() {
        AdminAccessValidator validator = new AdminAccessValidator("1");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(null, "any-session-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("관리자 userId는 콤마 구분 목록으로 여러 개 설정할 수 있다")
    void isAdminWithMultipleAdminUserIds() {
        AdminAccessValidator validator = new AdminAccessValidator("1, 2");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(2L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("관리자 userId 설정에 잘못된 값이 있어도 서버 부팅을 막지 않고 정상 값만 반영한다")
    void ignoreInvalidAdminUserIdSetting() {
        AdminAccessValidator validator = new AdminAccessValidator("1, abc, 2");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(2L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("관리자 userId 설정이 모두 잘못된 값이면 allow-list는 비어 있고 false를 반환한다")
    void rejectWhenAllAdminUserIdSettingsAreInvalid() {
        AdminAccessValidator validator = new AdminAccessValidator("abc, def");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("관리자 userId 설정이 비어 있으면 모든 사용자를 false로 판단한다")
    void rejectWhenAdminUserIdSettingIsEmpty() {
        AdminAccessValidator validator = new AdminAccessValidator("");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertFalse(validator.isAdmin(authentication));
    }

    @Test
    @DisplayName("관리자 userId 설정에 빈 토큰이 섞여 있어도 무시하고 정상 값만 반영한다")
    void ignoreBlankTokensInAdminUserIdSetting() {
        AdminAccessValidator validator = new AdminAccessValidator("1, , 2,   ");

        TestingAuthenticationToken authentication = authenticatedToken(
                new CustomPrincipal(1L, "any-session-identifier", UserType.REGISTERED)
        );

        assertTrue(validator.isAdmin(authentication));
    }

    private TestingAuthenticationToken authenticatedToken(Object principal) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                principal,
                null
        );
        authentication.setAuthenticated(true);
        return authentication;
    }
}