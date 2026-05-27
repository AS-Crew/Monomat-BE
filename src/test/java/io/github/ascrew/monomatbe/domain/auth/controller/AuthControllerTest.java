package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.service.GuestAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.LoginAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.LogoutAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.RefreshAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.RegisterAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void logout_nullPrincipal_returnsUnauthenticatedAuthError() {
        AuthController controller = new AuthController(
                mock(GuestAuthService.class),
                mock(RegisterAuthService.class),
                mock(LoginAuthService.class),
                mock(RefreshAuthService.class),
                mock(LogoutAuthService.class)
        );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> controller.logout(null, "Bearer token")
        );

        assertEquals(AuthErrorCode.AUTH_UNAUTHENTICATED, exception.getErrorCode());
    }

    @Test
    void guestLogin_whenTrustForwardedHeadersFalse_usesRemoteAddr() {
        GuestAuthService guestAuthService = mock(GuestAuthService.class);

        when(guestAuthService.loginAsGuest("guest", "127.0.0.10", "JUnit-Agent"))
                .thenReturn(GuestLoginResponse.builder()
                        .userId(1L)
                        .nickname("guest")
                        .userType(UserType.GUEST)
                        .userIdentifier("session")
                        .accessToken("a")
                        .refreshToken("r")
                        .build());

        AuthController controller = new AuthController(
                guestAuthService,
                mock(RegisterAuthService.class),
                mock(LoginAuthService.class),
                mock(RefreshAuthService.class),
                mock(LogoutAuthService.class)
        );

        ReflectionTestUtils.setField(controller, "trustForwardedHeaders", false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.1");
        request.addHeader("User-Agent", "JUnit-Agent");
        request.setRemoteAddr("127.0.0.10");

        assertDoesNotThrow(() -> controller.guestLogin(new GuestLoginRequest("guest"), request));

        verify(guestAuthService).loginAsGuest("guest", "127.0.0.10", "JUnit-Agent");
    }

    @Test
    void guestLogin_whenTrustForwardedHeadersTrue_usesForwardedForFirstIp() {
        GuestAuthService guestAuthService = mock(GuestAuthService.class);

        when(guestAuthService.loginAsGuest("guest", "198.51.100.10", "JUnit-Agent"))
                .thenReturn(GuestLoginResponse.builder()
                        .userId(1L)
                        .nickname("guest")
                        .userType(UserType.GUEST)
                        .userIdentifier("session")
                        .accessToken("a")
                        .refreshToken("r")
                        .build());

        AuthController controller = new AuthController(
                guestAuthService,
                mock(RegisterAuthService.class),
                mock(LoginAuthService.class),
                mock(RefreshAuthService.class),
                mock(LogoutAuthService.class)
        );

        ReflectionTestUtils.setField(controller, "trustForwardedHeaders", true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "198.51.100.10, 10.0.0.1");
        request.addHeader("User-Agent", "JUnit-Agent");
        request.setRemoteAddr("127.0.0.10");

        assertDoesNotThrow(() -> controller.guestLogin(new GuestLoginRequest("guest"), request));

        verify(guestAuthService).loginAsGuest("guest", "198.51.100.10", "JUnit-Agent");
    }
}