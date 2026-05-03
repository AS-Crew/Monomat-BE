package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.GuestLoginResponse;
import io.github.ascrew.monomatbe.domain.auth.service.GuestAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    /**
     * 게스트 로그인 엔드포인트
     * 닉네임 기반으로 게스트 계정/세션을 생성하고 토큰 정보를 반환
     */
    @PostMapping("/guest")
    public ResponseEntity<GuestLoginResponse> guestLogin(@Valid @RequestBody GuestLoginRequest request) {
        return ResponseEntity.ok(guestAuthService.loginAsGuest(request.nickname()));
    }
}
