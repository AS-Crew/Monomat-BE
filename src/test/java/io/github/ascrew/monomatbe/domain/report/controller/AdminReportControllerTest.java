package io.github.ascrew.monomatbe.domain.report.controller;

import io.github.ascrew.monomatbe.domain.report.dto.AdminReportDetailResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportListItemResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportPageResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportStatusUpdateRequest;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.service.AdminReportCommandService;
import io.github.ascrew.monomatbe.domain.report.service.AdminReportQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReportControllerTest {

    private static final Long REPORT_ID = 1L;

    @Mock
    private AdminReportQueryService adminReportQueryService;

    @Mock
    private AdminReportCommandService adminReportCommandService;

    private AdminReportController adminReportController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        adminReportController = new AdminReportController(
                adminReportQueryService,
                adminReportCommandService
        );
    }

    @Test
    @DisplayName("관리자 신고 목록 조회 요청을 QueryService로 위임하고 200을 반환한다")
    void getReports_delegatesToQueryService() {
        // given
        AdminReportPageResponse expected = AdminReportPageResponse.of(
                List.of(listItemResponse()),
                0,
                20,
                false
        );

        when(adminReportQueryService.getReports(
                ReportTargetType.LOBBY_USER,
                ReportStatus.PENDING,
                0,
                20
        )).thenReturn(expected);

        // when
        var response = adminReportController.getReports(
                ReportTargetType.LOBBY_USER,
                ReportStatus.PENDING,
                0,
                20
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(adminReportQueryService).getReports(
                ReportTargetType.LOBBY_USER,
                ReportStatus.PENDING,
                0,
                20
        );
    }

    @Test
    @DisplayName("관리자 신고 상세 조회 요청을 QueryService로 위임하고 200을 반환한다")
    void getReportDetail_delegatesToQueryService() {
        // given
        AdminReportDetailResponse expected = detailResponse();

        when(adminReportQueryService.getReportDetail(REPORT_ID))
                .thenReturn(expected);

        // when
        var response = adminReportController.getReportDetail(REPORT_ID);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(adminReportQueryService).getReportDetail(REPORT_ID);
    }

    @Test
    @DisplayName("관리자 신고 처리 상태 변경 요청을 CommandService로 위임하고 204를 반환한다")
    void updateReportStatus_delegatesToCommandService() {
        // given
        AdminReportStatusUpdateRequest request =
                new AdminReportStatusUpdateRequest(ReportStatus.RESOLVED);

        // when
        var response = adminReportController.updateReportStatus(
                REPORT_ID,
                request
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();

        verify(adminReportCommandService).updateReportStatus(
                REPORT_ID,
                ReportStatus.RESOLVED
        );
    }

    private AdminReportListItemResponse listItemResponse() {
        return AdminReportListItemResponse.builder()
                .reportId(REPORT_ID)
                .reporterId(10L)
                .reporterUsername("reporter")
                .lobbyId(20L)
                .lobbyCode("ABC123")
                .lobbyTitle("신고 테스트 로비")
                .targetType(ReportTargetType.LOBBY_USER)
                .targetId(30L)
                .targetReference(null)
                .reason("부적절한 사용자")
                .status(ReportStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 5, 31, 12, 0))
                .resolvedAt(null)
                .build();
    }

    private AdminReportDetailResponse detailResponse() {
        return AdminReportDetailResponse.builder()
                .reportId(REPORT_ID)
                .reporterId(10L)
                .reporterUsername("reporter")
                .lobbyId(20L)
                .lobbyCode("ABC123")
                .lobbyTitle("신고 테스트 로비")
                .targetType(ReportTargetType.LOBBY_USER)
                .targetId(30L)
                .targetReference(null)
                .reason("부적절한 사용자")
                .status(ReportStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 5, 31, 12, 0))
                .resolvedAt(null)
                .chatMessageSnapshot(null)
                .build();
    }
}