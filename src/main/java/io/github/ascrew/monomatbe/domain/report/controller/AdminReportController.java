package io.github.ascrew.monomatbe.domain.report.controller;

import io.github.ascrew.monomatbe.domain.report.dto.AdminReportPageResponse;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.service.AdminReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 신고 REST API 컨트롤러
 *
 * [책임]
 * - 신고 목록 조회
 * - 신고 상세 조회
 * - 신고 처리 상태 변경
 *
 * 이번 단계에서는 신고 목록 조회만 구현한다.
 */
@Slf4j
@Tag(name = "Admin Report", description = "관리자 신고 관리 API")
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private static final String LOG_GET_REPORTS_REQUEST =
            "요청 수신: 관리자 신고 목록 조회 [GET /api/admin/reports] - targetType: {}, status: {}, page: {}, size: {}";
    private static final String LOG_GET_REPORTS_RESPONSE =
            "관리자 신고 목록 조회 완료 - count: {}, page: {}, size: {}, hasNext: {}";

    private final AdminReportQueryService adminReportQueryService;

    /**
     * 관리자 신고 목록 조회 API.
     *
     * [권한]
     * - ROLE_ADMIN 필요
     *
     * [필터]
     * - targetType: LOBBY, LOBBY_USER, LOBBY_CHAT_MESSAGE
     * - status: PENDING, RESOLVED, DISMISSED
     */
    @Operation(
            summary = "관리자 신고 목록 조회",
            description = """
                    관리자 권한으로 신고 목록을 조회합니다.
                    targetType과 status로 필터링할 수 있으며, page/size 기반 페이징을 지원합니다.
                    """
    )
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminReportPageResponse> getReports(
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        log.info(
                LOG_GET_REPORTS_REQUEST,
                targetType,
                status,
                page,
                size
        );

        AdminReportPageResponse response = adminReportQueryService.getReports(
                targetType,
                status,
                page,
                size
        );

        log.info(
                LOG_GET_REPORTS_RESPONSE,
                response.items().size(),
                response.page(),
                response.size(),
                response.hasNext()
        );

        return ResponseEntity.ok(response);
    }
}