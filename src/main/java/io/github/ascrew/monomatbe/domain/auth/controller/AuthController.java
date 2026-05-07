package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginResponse;
import io.github.ascrew.monomatbe.domain.auth.dto.RegisterRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.RegisterResponse;
import io.github.ascrew.monomatbe.domain.auth.service.GuestAuthService;
import io.github.ascrew.monomatbe.domain.auth.service.RegisterAuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final GuestAuthService guestAuthService;
    private final RegisterAuthService registerAuthService;

    /**
     * 게스트 로그인 엔드포인트
     * 닉네임 기반으로 게스트 계정/세션을 생성하고 토큰 정보를 반환
     */
    @Operation(summary = "게스트 로그인", description = "닉네임으로 게스트 계정/세션을 생성하고 토큰 정보를 반환합니다.")
    @PostMapping("/guest")
    public ResponseEntity<GuestLoginResponse> guestLogin(@Valid @RequestBody GuestLoginRequest request) {
        return ResponseEntity.ok(guestAuthService.loginAsGuest(request.nickname()));
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
}
