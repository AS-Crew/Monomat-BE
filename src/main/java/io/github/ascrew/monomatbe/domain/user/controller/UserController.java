package io.github.ascrew.monomatbe.domain.user.controller;

import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.service.UserQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 조회 REST API를 처리하는 컨트롤러
 *
 * Controller는 HTTP 요청을 받고 인증 주체를 Service에 전달하는 역할만 담당한다.
 * 사용자 상태 검증, 조회 정책, 응답 DTO 변환은 UserQueryService에서 처리한다.
 */
@Slf4j
@Tag(name = "User", description = "사용자 REST API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private static final String LOG_GET_MY_INFO_REQUEST =
            "요청 수신: 내 사용자 정보 조회 [GET /api/users/me] - userId: {}, userIdentifier: {}";
    private static final String LOG_GET_MY_INFO_RESPONSE =
            "조회 완료: 내 사용자 정보 반환 - userId: {}, userType: {}, status: {}";

    private final UserQueryService userQueryService;

    /**
     * 로그인한 사용자의 기본 정보를 조회
     *
     * @param principal JWT 인증 후 SecurityContext에 저장된 사용자 주체
     * @return 내 사용자 기본 정보
     */
    @Operation(
            summary = "내 사용자 정보 조회",
            description = "로그인한 사용자의 기본 정보(userId, username, userType, status, createdAt)를 조회합니다."
    )
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MyUserInfoResponse> getMyInfo(
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_GET_MY_INFO_REQUEST,
                principal != null ? principal.userId() : null,
                principal != null ? principal.userIdentifier() : null
        );

        MyUserInfoResponse response = userQueryService.getMyInfo(principal);

        log.info(
                LOG_GET_MY_INFO_RESPONSE,
                response.userId(),
                response.userType(),
                response.status()
        );

        return ResponseEntity.ok(response);
    }
}