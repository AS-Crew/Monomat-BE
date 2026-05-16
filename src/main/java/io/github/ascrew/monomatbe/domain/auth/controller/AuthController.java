package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginResponse;
import io.github.ascrew.monomatbe.domain.auth.dto.LoginRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.LoginResponse;
import io.github.ascrew.monomatbe.domain.auth.dto.LogoutResponse;
import io.github.ascrew.monomatbe.domain.auth.dto.RefreshTokenRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.RefreshTokenResponse;
import io.github.ascrew.monomatbe.domain.auth.dto.RegisterRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.RegisterResponse;
import io.github.ascrew.monomatbe.domain.auth.service.GuestAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.LoginAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.LogoutAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.RefreshAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.RegisterAuthService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final GuestAuthService guestAuthService;
    private final RegisterAuthService registerAuthService;
    private final LoginAuthService loginAuthService;
    private final RefreshAuthService refreshAuthService;
    private final LogoutAuthService logoutAuthService;

    @Value("${auth.network.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    /**
     * 게스트 로그인 엔드포인트
     * 닉네임 기반으로 게스트 계정/세션을 생성하고 토큰 정보를 반환
     */
    @Operation(summary = "게스트 로그인", description = "닉네임으로 게스트 계정/세션을 생성하고 토큰 정보를 반환합니다.")
    @PostMapping("/guest")
    public ResponseEntity<GuestLoginResponse> guestLogin(
            @Valid @RequestBody GuestLoginRequest request,
            HttpServletRequest httpServletRequest
    ) {
        return ResponseEntity.ok(guestAuthService.loginAsGuest(
                request.nickname(),
                resolveClientIp(httpServletRequest),
                httpServletRequest.getHeader("User-Agent")
        ));
    }

    /**
     * 회원가입 엔드포인트.
     * (#34 범위: 계정 생성만 처리, 토큰 발급은 #35 로그인에서 처리)
     */
    @Operation(summary = "회원가입", description = "로그인 ID/비밀번호/닉네임으로 정식 회원 계정을 생성합니다.")
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = registerAuthService.register(
                request.loginId(),
                request.password(),
                request.nickname()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 자체 로그인 엔드포인트.
     */
    @Operation(summary = "자체 로그인", description = "로그인 ID/비밀번호로 인증하고 토큰/세션 정보를 발급합니다.")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpServletRequest
    ) {
        LoginResponse response = loginAuthService.login(
                request.loginId(),
                request.password(),
                resolveClientIp(httpServletRequest),
                httpServletRequest.getHeader("User-Agent")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "토큰 재발급", description = "Refresh Token Rotation(RTR)으로 Access/Refresh 토큰을 재발급합니다.")
    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpServletRequest
    ) {
        RefreshTokenResponse response = refreshAuthService.refresh(
                request.refreshToken(),
                resolveClientIp(httpServletRequest),
                httpServletRequest.getHeader("User-Agent")
        );
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "로그아웃", description = "Access Token을 블랙리스트 처리하고 현재 세션을 종료합니다.")
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(
            @AuthenticationPrincipal CustomPrincipal principal,
            @RequestHeader("Authorization") String authorizationHeader
    ) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 정보가 없습니다.");
        }
        logoutAuthService.logout(principal.userId(), principal.userIdentifier(), authorizationHeader);
        return ResponseEntity.ok(new LogoutResponse("로그아웃이 완료되었습니다."));
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
