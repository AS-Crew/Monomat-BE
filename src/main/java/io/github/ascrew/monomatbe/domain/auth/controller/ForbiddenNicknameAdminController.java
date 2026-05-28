package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameCreateRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameResponse;
import io.github.ascrew.monomatbe.domain.auth.service.AdminAccessValidator;
import io.github.ascrew.monomatbe.domain.auth.service.ForbiddenNicknameService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 닉네임 금칙어 관리 API
 *
 * [현재 권한 정책]
 * - JWT 인증은 Spring Security @PreAuthorize로 1차 확인한다.
 * - 관리자 여부는 AdminAccessValidator가 설정 기반 allow-list로 2차 확인한다.
 *
 * [주의]
 * - 현재 프로젝트에 ROLE_ADMIN 권한 체계가 없으므로 hasRole('ADMIN')을 사용하지 않는다.
 * - 추후 관리자 권한 체계가 추가되면 AdminAccessValidator 내부 구현을 교체한다.
 */
@Tag(name = "Forbidden Nickname Admin", description = "관리자 닉네임 금칙어 관리 API")
@RestController
@RequestMapping("/api/admin/forbidden-nicknames")
@RequiredArgsConstructor
public class ForbiddenNicknameAdminController {

    private final ForbiddenNicknameService forbiddenNicknameService;
    private final AdminAccessValidator adminAccessValidator;

    @Operation(summary = "닉네임 금칙어 목록 조회")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ForbiddenNicknameResponse>> getForbiddenNicknames(
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        adminAccessValidator.validate(principal);

        List<ForbiddenNicknameResponse> response = forbiddenNicknameService.getForbiddenWords()
                .stream()
                .map(ForbiddenNicknameResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "닉네임 금칙어 추가")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ForbiddenNicknameResponse> createForbiddenNickname(
            @Valid @RequestBody ForbiddenNicknameCreateRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        adminAccessValidator.validate(principal);

        ForbiddenNicknameResponse response = ForbiddenNicknameResponse.from(
                forbiddenNicknameService.addForbiddenWord(request.word())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "닉네임 금칙어 삭제")
    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteForbiddenNickname(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        adminAccessValidator.validate(principal);

        forbiddenNicknameService.deleteForbiddenWord(id);

        return ResponseEntity.noContent().build();
    }
}