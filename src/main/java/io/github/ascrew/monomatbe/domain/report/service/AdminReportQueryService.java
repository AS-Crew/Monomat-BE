package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.report.dto.AdminReportListItemResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportPageResponse;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 관리자 신고 조회 서비스
 *
 * [책임]
 * - 신고 목록 필터링
 * - 페이징 파라미터 검증
 * - 관리자 목록 응답 DTO 변환
 */
@Service
@RequiredArgsConstructor
public class AdminReportQueryService {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private static final String ERROR_INVALID_PAGE =
            "page는 0 이상이어야 합니다.";
    private static final String ERROR_INVALID_SIZE =
            "size는 1 이상 100 이하이어야 합니다.";

    private final ReportRepository reportRepository;

    /**
     * 관리자 신고 목록을 조회한다.
     *
     * @param targetType 신고 대상 타입 필터. null이면 전체
     * @param status     신고 처리 상태 필터. null이면 전체
     * @param page       0-based 페이지 번호
     * @param size       페이지 크기
     * @return 신고 목록 페이징 응답
     */
    public AdminReportPageResponse getReports(
            ReportTargetType targetType,
            ReportStatus status,
            Integer page,
            Integer size
    ) {
        int resolvedPage = resolvePage(page);
        int resolvedSize = resolveSize(size);

        Pageable pageable = PageRequest.of(
                resolvedPage,
                resolvedSize,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<Report> reportPage = findReports(targetType, status, pageable);

        List<AdminReportListItemResponse> items = reportPage.getContent()
                .stream()
                .map(this::toListItemResponse)
                .toList();

        return AdminReportPageResponse.of(
                items,
                resolvedPage,
                resolvedSize,
                reportPage.hasNext()
        );
    }

    private Page<Report> findReports(
            ReportTargetType targetType,
            ReportStatus status,
            Pageable pageable
    ) {
        if (targetType != null && status != null) {
            return reportRepository.findByTargetTypeAndStatus(targetType, status, pageable);
        }

        if (targetType != null) {
            return reportRepository.findByTargetType(targetType, pageable);
        }

        if (status != null) {
            return reportRepository.findByStatus(status, pageable);
        }

        return reportRepository.findAllBy(pageable);
    }

    private int resolvePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }

        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_PAGE);
        }

        return page;
    }

    private int resolveSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }

        if (size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_SIZE);
        }

        return size;
    }

    private AdminReportListItemResponse toListItemResponse(Report report) {
        return AdminReportListItemResponse.builder()
                .reportId(report.getId())
                .reporterId(report.getReporter().getId())
                .reporterUsername(report.getReporter().getUsername())
                .lobbyId(report.getLobby().getId())
                .lobbyCode(report.getLobby().getInviteCode())
                .lobbyTitle(report.getLobby().getTitle())
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .targetReference(report.getTargetReference())
                .reason(report.getReason())
                .status(report.getStatus())
                .createdAt(report.getCreatedAt())
                .resolvedAt(report.getResolvedAt())
                .build();
    }
}