package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameCreateRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameResponse;
import io.github.ascrew.monomatbe.domain.auth.service.ForbiddenNicknameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * - 현재 프로젝트에 ROLE_ADMIN 권한 체계가 없으므로 users.id allow-list 기반으로 관리자 접근을 제한한다.
 * - 관리자 여부는 @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")에서 중앙집중적으로 판단한다.
 *
 * [주의]
 * - Controller 내부에서 수동으로 관리자 검증 메서드를 호출하지 않는다.
 * - 관리자 검증을 수동 호출로 분산하면 새 엔드포인트 추가 시 누락 위험이 생긴다.
 * - 추후 ROLE_ADMIN 권한 체계가 추가되면 AdminAccessValidator 내부 구현만 교체한다.
 */
@Tag(name = "Forbidden Nickname Admin", description = "관리자 닉네임 금칙어 관리 API")
@RestController
@RequestMapping("/api/admin/forbidden-nicknames")
@RequiredArgsConstructor
public class ForbiddenNicknameAdminController {

    private final ForbiddenNicknameService forbiddenNicknameService;

    @Operation(summary = "닉네임 금칙어 목록 조회")
    @GetMapping
    @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")
    public ResponseEntity<List<ForbiddenNicknameResponse>> getForbiddenNicknames() {
        List<ForbiddenNicknameResponse> response = forbiddenNicknameService.getForbiddenWords()
                .stream()
                .map(ForbiddenNicknameResponse::from)
                .toList();

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "닉네임 금칙어 추가")
    @PostMapping
    @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")
    public ResponseEntity<ForbiddenNicknameResponse> createForbiddenNickname(
            @Valid @RequestBody ForbiddenNicknameCreateRequest request
    ) {
        ForbiddenNicknameResponse response = ForbiddenNicknameResponse.from(
                forbiddenNicknameService.addForbiddenWord(request.word())
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "닉네임 금칙어 삭제")
    @DeleteMapping("/{id}")
    @PreAuthorize("@adminAccessValidator.isAdmin(authentication)")
    public ResponseEntity<Void> deleteForbiddenNickname(@PathVariable Long id) {
        forbiddenNicknameService.deleteForbiddenWord(id);

        return ResponseEntity.noContent().build();
    }
}