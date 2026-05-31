package io.github.ascrew.monomatbe.domain.report.controller;

import io.github.ascrew.monomatbe.domain.report.dto.LobbyChatMessageReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyUserReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.ReportResponse;
import io.github.ascrew.monomatbe.domain.report.service.LobbyChatMessageReportService;
import io.github.ascrew.monomatbe.domain.report.service.ReportService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신고 REST API 컨트롤러
 *
 * [책임]
 * - 로비 신고 요청 수신
 * - 로비 내 유저 신고 요청 수신
 * - 로비 채팅 메시지 신고 요청 수신
 * - 인증 주체에서 reporterId / userIdentifier 추출
 * - 요청 DTO 검증 위임
 *
 * [URL 설계]
 * 신고 기능은 로비 화면에서 발생하므로 /api/lobbies 하위에 배치한다.
 * 다만 도메인 책임은 report로 분리해 ReportController에서 처리한다.
 */
@Slf4j
@Tag(name = "Report", description = "신고 REST API")
@RestController
@RequestMapping("/api/lobbies")
@RequiredArgsConstructor
public class ReportController {

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_LOBBY_REPORT_REQUEST =
            "요청 수신: 로비 신고 [POST /api/lobbies/{code}/reports] - 코드: {}, 신고자: {}";
    private static final String LOG_LOBBY_REPORT_RESPONSE =
            "로비 신고 접수 응답 - reportId: {}, 코드: {}";
    private static final String LOG_LOBBY_USER_REPORT_REQUEST =
            "요청 수신: 로비 유저 신고 [POST /api/lobbies/{code}/users/{targetUserId}/reports] - 코드: {}, 신고자: {}, 대상 유저: {}";
    private static final String LOG_LOBBY_USER_REPORT_RESPONSE =
            "로비 유저 신고 접수 응답 - reportId: {}, 코드: {}, 대상 유저: {}";
    private static final String LOG_LOBBY_CHAT_MESSAGE_REPORT_REQUEST =
            "요청 수신: 로비 채팅 메시지 신고 [POST /api/lobbies/{code}/chats/{messageId}/reports] - 코드: {}, 신고자: {}, 메시지: {}";
    private static final String LOG_LOBBY_CHAT_MESSAGE_REPORT_RESPONSE =
            "로비 채팅 메시지 신고 접수 응답 - reportId: {}, 코드: {}, 메시지: {}";

    private final ReportService reportService;
    private final LobbyChatMessageReportService lobbyChatMessageReportService;

    /**
     * 로비 자체 신고 API
     *
     * [인증]
     * JWT Access Token이 필요하다.
     * 게스트와 정식 회원 모두 신고할 수 있다.
     *
     * [신고 대상]
     * - targetType: LOBBY
     * - targetId: game_lobby.id
     *
     * [중복 신고 정책]
     * 동일 사용자가 동일 로비에 대해 PENDING 신고를 이미 생성했다면
     * 409 Conflict를 반환한다.
     *
     * @param code      로비 초대 코드
     * @param request   신고 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 201 Created + 신고 생성 응답
     */
    @Operation(
            summary = "로비 신고",
            description = """
                    부적절한 로비 제목 또는 로비 설정을 신고합니다.
                    동일 사용자가 같은 로비에 대해 처리되지 않은 신고를 중복 생성할 수 없습니다.
                    JWT 인증이 필요합니다.
                    """
    )
    @PostMapping("/{code}/reports")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReportResponse> reportLobby(
            @PathVariable String code,
            @Valid @RequestBody LobbyReportRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_LOBBY_REPORT_REQUEST,
                code,
                principal != null ? principal.userId() : null
        );

        ReportResponse response = reportService.reportLobby(
                code,
                principal != null ? principal.userId() : null,
                request
        );

        log.info(LOG_LOBBY_REPORT_RESPONSE, response.reportId(), code);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 로비 내 특정 유저 신고 API
     *
     * [인증]
     * JWT Access Token이 필요하다.
     * 게스트와 정식 회원 모두 신고할 수 있다.
     *
     * [신고 대상]
     * - targetType: LOBBY_USER
     * - targetId: users.id
     *
     * [제한]
     * 자기 자신은 신고할 수 없다.
     * 동일 사용자가 동일 로비의 동일 유저에 대해 PENDING 신고를 이미 생성했다면
     * 409 Conflict를 반환한다.
     *
     * @param code         로비 초대 코드
     * @param targetUserId 신고 대상 users.id
     * @param request      신고 요청 DTO
     * @param principal    JWT에서 추출한 인증 주체
     * @return 201 Created + 신고 생성 응답
     */
    @Operation(
            summary = "로비 유저 신고",
            description = """
                    특정 로비 안의 유저를 신고합니다.
                    자기 자신은 신고할 수 없으며,
                    동일 사용자가 같은 로비의 같은 유저에 대해 처리되지 않은 신고를 중복 생성할 수 없습니다.
                    JWT 인증이 필요합니다.
                    """
    )
    @PostMapping("/{code}/users/{targetUserId}/reports")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReportResponse> reportLobbyUser(
            @PathVariable String code,
            @PathVariable Long targetUserId,
            @Valid @RequestBody LobbyUserReportRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_LOBBY_USER_REPORT_REQUEST,
                code,
                principal != null ? principal.userId() : null,
                targetUserId
        );

        ReportResponse response = reportService.reportLobbyUser(
                code,
                principal != null ? principal.userId() : null,
                targetUserId,
                request
        );

        log.info(
                LOG_LOBBY_USER_REPORT_RESPONSE,
                response.reportId(),
                code,
                targetUserId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 로비 채팅 메시지 신고 API
     *
     * [인증]
     * JWT Access Token이 필요하다.
     * 게스트와 정식 회원 모두 신고할 수 있다.
     *
     * [신고 대상]
     * - targetType: LOBBY_CHAT_MESSAGE
     * - messageId: Redis 최근 채팅 메시지 식별자
     *
     * [제한]
     * - 현재 로비 참여자만 신고할 수 있다.
     * - 강퇴된 사용자는 신고할 수 없다.
     * - 자기 자신이 작성한 메시지는 신고할 수 없다.
     * - 동일 사용자가 같은 로비의 같은 채팅 메시지에 대해 처리되지 않은 신고를 중복 생성할 수 없다.
     *
     * [스냅샷]
     * Redis 최근 채팅은 TTL이 있으므로 신고 시점의 메시지 원문, 발신자, 발신 시각을 DB 스냅샷으로 저장한다.
     *
     * @param code      로비 초대 코드
     * @param messageId 신고 대상 채팅 메시지 ID
     * @param request   신고 요청 DTO
     * @param principal JWT에서 추출한 인증 주체
     * @return 201 Created + 신고 생성 응답
     */
    @Operation(
            summary = "로비 채팅 메시지 신고",
            description = """
                    특정 로비 안의 채팅 메시지를 신고합니다.
                    현재 로비 참여자만 신고할 수 있으며, 강퇴된 사용자는 신고할 수 없습니다.
                    자기 자신이 작성한 메시지는 신고할 수 없습니다.
                    동일 사용자가 같은 로비의 같은 채팅 메시지에 대해 처리되지 않은 신고를 중복 생성할 수 없습니다.
                    신고 접수 시점의 채팅 메시지 원문과 발신자 정보를 DB 스냅샷으로 저장합니다.
                    JWT 인증이 필요합니다.
                    """
    )
    @PostMapping("/{code}/chats/{messageId}/reports")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ReportResponse> reportLobbyChatMessage(
            @PathVariable String code,
            @PathVariable String messageId,
            @Valid @RequestBody LobbyChatMessageReportRequest request,
            @AuthenticationPrincipal CustomPrincipal principal
    ) {
        log.info(
                LOG_LOBBY_CHAT_MESSAGE_REPORT_REQUEST,
                code,
                principal != null ? principal.userId() : null,
                messageId
        );

        ReportResponse response = lobbyChatMessageReportService.reportLobbyChatMessage(
                code,
                messageId,
                principal != null ? principal.userId() : null,
                principal != null ? principal.userIdentifier() : null,
                request
        );

        log.info(
                LOG_LOBBY_CHAT_MESSAGE_REPORT_RESPONSE,
                response.reportId(),
                code,
                messageId
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}