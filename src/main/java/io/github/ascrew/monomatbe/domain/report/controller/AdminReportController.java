package io.github.ascrew.monomatbe.domain.report.controller;

import io.github.ascrew.monomatbe.domain.report.dto.AdminReportDetailResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportPageResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportStatusUpdateRequest;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.service.AdminReportCommandService;
import io.github.ascrew.monomatbe.domain.report.service.AdminReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
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
    private static final String LOG_GET_REPORT_DETAIL_REQUEST =
            "요청 수신: 관리자 신고 상세 조회 [GET /api/admin/reports/{reportId}] - reportId: {}";
    private static final String LOG_GET_REPORT_DETAIL_RESPONSE =
            "관리자 신고 상세 조회 완료 - reportId: {}, status: {}";
    private static final String LOG_UPDATE_REPORT_STATUS_REQUEST =
            "요청 수신: 관리자 신고 처리 상태 변경 [PATCH /api/admin/reports/{reportId}/status] - reportId: {}, status: {}";
    private static final String LOG_UPDATE_REPORT_STATUS_RESPONSE =
            "관리자 신고 처리 상태 변경 완료 - reportId: {}, status: {}";

    private final AdminReportQueryService adminReportQueryService;
    private final AdminReportCommandService adminReportCommandService;

    /**
     * 관리자 신고 목록 조회 API
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

    /**
     * 관리자 신고 상세 조회 API
     *
     * [권한]
     * - ROLE_ADMIN 필요
     *
     * [응답]
     * - 공통 신고 정보
     * - 신고자 정보
     * - 로비 맥락 정보
     * - 채팅 메시지 신고인 경우 메시지 스냅샷
     */
    @Operation(
            summary = "관리자 신고 상세 조회",
            description = """
                    관리자 권한으로 특정 신고의 상세 정보를 조회합니다.
                    채팅 메시지 신고인 경우 신고 시점의 채팅 메시지 스냅샷을 함께 반환합니다.
                    """
    )
    @GetMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminReportDetailResponse> getReportDetail(
            @PathVariable Long reportId
    ) {
        log.info(LOG_GET_REPORT_DETAIL_REQUEST, reportId);

        AdminReportDetailResponse response = adminReportQueryService.getReportDetail(reportId);

        log.info(
                LOG_GET_REPORT_DETAIL_RESPONSE,
                response.reportId(),
                response.status()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 관리자 신고 처리 상태 변경 API
     *
     * [권한]
     * - ROLE_ADMIN 필요
     *
     * [허용 상태]
     * - RESOLVED
     * - DISMISSED
     *
     * [정책]
     * 이미 처리된 신고는 다시 처리할 수 없다.
     */
    @Operation(
            summary = "관리자 신고 처리 상태 변경",
            description = """
                    관리자 권한으로 PENDING 상태의 신고를 RESOLVED 또는 DISMISSED 상태로 변경합니다.
                    이미 처리된 신고는 다시 처리할 수 없습니다.
                    """
    )
    @PatchMapping("/{reportId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateReportStatus(
            @PathVariable Long reportId,
            @Valid @RequestBody AdminReportStatusUpdateRequest request
    ) {
        log.info(
                LOG_UPDATE_REPORT_STATUS_REQUEST,
                reportId,
                request.status()
        );

        adminReportCommandService.updateReportStatus(
                reportId,
                request.status()
        );

        log.info(
                LOG_UPDATE_REPORT_STATUS_RESPONSE,
                reportId,
                request.status()
        );

        return ResponseEntity.noContent().build();
    }
}