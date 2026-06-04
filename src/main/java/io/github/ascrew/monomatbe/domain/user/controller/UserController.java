package io.github.ascrew.monomatbe.domain.user.controller;

import io.github.ascrew.monomatbe.domain.user.dto.ChangePasswordRequest;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.dto.UpdateNicknameRequest;
import io.github.ascrew.monomatbe.domain.user.service.UserCommandService;
import io.github.ascrew.monomatbe.domain.user.service.UserQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 REST API를 처리하는 컨트롤러
 *
 * Controller는 HTTP 요청을 받고 인증 주체를 Service에 전달하는 역할만 담당한다.
 * 사용자 상태 검증, 조회/수정 정책, 응답 DTO 변환은 Service에서 처리한다.
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
    private static final String LOG_UPDATE_MY_NICKNAME_REQUEST =
            "요청 수신: 내 닉네임 변경 [PATCH /api/users/me/nickname] - userId: {}, userIdentifier: {}";
    private static final String LOG_UPDATE_MY_NICKNAME_RESPONSE =
            "변경 완료: 내 닉네임 변경 - userId: {}, username: {}";
    private static final String LOG_CHANGE_MY_PASSWORD_REQUEST =
            "요청 수신: 내 비밀번호 변경 [PATCH /api/users/me/password] - userId: {}, userIdentifier: {}";
    private static final String LOG_CHANGE_MY_PASSWORD_RESPONSE =
            "변경 완료: 내 비밀번호 변경 및 활성 세션 만료 - userId: {}";

    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;

    /**
     * 로그인한 사용자의 기본 정보를 조회한다.
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

    /**
     * 로그인한 정식 회원의 닉네임을 변경한다.
     *
     * @param principal JWT 인증 후 SecurityContext에 저장된 사용자 주체
     * @param request 닉네임 변경 요청
     * @return 변경 후 내 사용자 기본 정보
     */
    @Operation(
            summary = "내 닉네임 변경",
            description = """
                    로그인한 정식 회원의 닉네임을 변경합니다.
                    게스트 사용자는 닉네임을 변경할 수 없습니다.
                    닉네임 변경 후 채팅 발신자 프로필 Redis 캐시를 무효화합니다.
                    """
    )
    @PatchMapping("/me/nickname")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MyUserInfoResponse> updateMyNickname(
            @AuthenticationPrincipal CustomPrincipal principal,
            @Valid @RequestBody UpdateNicknameRequest request
    ) {
        log.info(
                LOG_UPDATE_MY_NICKNAME_REQUEST,
                principal != null ? principal.userId() : null,
                principal != null ? principal.userIdentifier() : null
        );

        MyUserInfoResponse response = userCommandService.updateMyNickname(principal, request);

        log.info(
                LOG_UPDATE_MY_NICKNAME_RESPONSE,
                response.userId(),
                response.username()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 로그인한 정식 회원의 비밀번호를 변경한다.
     *
     * @param principal JWT 인증 후 SecurityContext에 저장된 사용자 주체
     * @param request 비밀번호 변경 요청
     * @return 204 No Content
     */
    @Operation(
            summary = "내 비밀번호 변경",
            description = """
                    로그인한 정식 회원의 비밀번호를 변경합니다.
                    게스트 사용자는 비밀번호를 변경할 수 없습니다.
                    현재 비밀번호가 일치해야 새 비밀번호로 변경할 수 있습니다.
                    새 비밀번호는 회원가입과 동일한 비밀번호 정책을 따릅니다.
                    비밀번호 변경 성공 후 모든 활성 세션을 만료합니다.
                    따라서 클라이언트는 성공 응답 수신 후 로그인 화면으로 이동해야 합니다.
                    """
    )
    @PatchMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changeMyPassword(
            @AuthenticationPrincipal CustomPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        log.info(
                LOG_CHANGE_MY_PASSWORD_REQUEST,
                principal != null ? principal.userId() : null,
                principal != null ? principal.userIdentifier() : null
        );

        userCommandService.changeMyPassword(principal, request);

        log.info(
                LOG_CHANGE_MY_PASSWORD_RESPONSE,
                principal != null ? principal.userId() : null
        );

        return ResponseEntity.noContent().build();
    }
}