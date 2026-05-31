package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자 신고 처리 Command 서비스
 *
 * [책임]
 * - 신고 처리 상태 변경
 * - 이미 처리된 신고 재처리 방지
 * - PENDING으로 되돌리는 잘못된 요청 차단
 */
@Service
@RequiredArgsConstructor
public class AdminReportCommandService {

    private static final String ERROR_REPORT_NOT_FOUND =
            "존재하지 않는 신고입니다.";
    private static final String ERROR_INVALID_TARGET_STATUS =
            "신고 처리 상태는 RESOLVED 또는 DISMISSED만 지정할 수 있습니다.";
    private static final String ERROR_REPORT_ALREADY_PROCESSED =
            "이미 처리된 신고입니다.";

    private final ReportRepository reportRepository;

    /**
     * 신고 처리 상태를 변경한다.
     *
     * [허용 전이]
     * - PENDING -> RESOLVED
     * - PENDING -> DISMISSED
     *
     * [차단 전이]
     * - RESOLVED -> *
     * - DISMISSED -> *
     * - * -> PENDING
     *
     * @param reportId 신고 ID
     * @param status   변경할 처리 상태
     */
    @Transactional
    public void updateReportStatus(Long reportId, ReportStatus status) {
        validateTargetStatus(status);

        Report report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_REPORT_NOT_FOUND
                ));

        if (report.getStatus() != ReportStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_REPORT_ALREADY_PROCESSED
            );
        }

        applyStatus(report, status);
    }

    private void validateTargetStatus(ReportStatus status) {
        if (status == ReportStatus.RESOLVED || status == ReportStatus.DISMISSED) {
            return;
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                ERROR_INVALID_TARGET_STATUS
        );
    }

    private void applyStatus(Report report, ReportStatus status) {
        switch (status) {
            case RESOLVED -> report.resolve();
            case DISMISSED -> report.dismiss();
            case PENDING -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_TARGET_STATUS
            );
        }
    }
}