package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.report.dto.AdminReportChatMessageSnapshotResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportDetailResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportListItemResponse;
import io.github.ascrew.monomatbe.domain.report.dto.AdminReportPageResponse;
import io.github.ascrew.monomatbe.domain.report.entity.LobbyChatMessageReportSnapshot;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.AdminReportSearchCondition;
import io.github.ascrew.monomatbe.domain.report.repository.LobbyChatMessageReportSnapshotRepository;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 관리자 신고 조회 서비스.
 *
 * [책임]
 * - 신고 목록 필터링
 * - 신고 상세 조회
 * - 페이징 파라미터 검증
 * - 관리자 응답 DTO 변환
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
    private static final String ERROR_REPORT_NOT_FOUND =
            "존재하지 않는 신고입니다.";

    private final ReportRepository reportRepository;
    private final LobbyChatMessageReportSnapshotRepository chatMessageReportSnapshotRepository;

    /**
     * 관리자 신고 목록을 조회한다.
     *
     * 검색 조건은 AdminReportSearchCondition으로 묶어 Repository에 전달한다.
     * 향후 조건이 늘어나도 Service의 if 분기와 Repository 메서드 조합을 늘리지 않는다.
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

        AdminReportSearchCondition condition = new AdminReportSearchCondition(
                targetType,
                status
        );

        Slice<Report> reportSlice = reportRepository.searchAdminReports(
                condition,
                pageable
        );

        List<AdminReportListItemResponse> items = reportSlice.getContent()
                .stream()
                .map(this::toListItemResponse)
                .toList();

        return AdminReportPageResponse.of(
                items,
                resolvedPage,
                resolvedSize,
                reportSlice.hasNext()
        );
    }

    /**
     * 관리자 신고 상세 정보를 조회한다.
     *
     * @param reportId 신고 ID
     * @return 신고 상세 응답
     */
    public AdminReportDetailResponse getReportDetail(Long reportId) {
        Report report = reportRepository.findDetailById(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_REPORT_NOT_FOUND
                ));

        return toDetailResponse(report);
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

    private AdminReportDetailResponse toDetailResponse(Report report) {
        return AdminReportDetailResponse.builder()
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
                .chatMessageSnapshot(resolveChatMessageSnapshot(report))
                .build();
    }

    private AdminReportChatMessageSnapshotResponse resolveChatMessageSnapshot(Report report) {
        if (report.getTargetType() != ReportTargetType.LOBBY_CHAT_MESSAGE) {
            return null;
        }

        return chatMessageReportSnapshotRepository.findByReportId(report.getId())
                .map(this::toChatMessageSnapshotResponse)
                .orElse(null);
    }

    private AdminReportChatMessageSnapshotResponse toChatMessageSnapshotResponse(
            LobbyChatMessageReportSnapshot snapshot
    ) {
        return AdminReportChatMessageSnapshotResponse.builder()
                .snapshotId(snapshot.getId())
                .messageId(snapshot.getMessageId())
                .senderIdentifier(snapshot.getSenderIdentifier())
                .senderId(snapshot.getSenderId())
                .senderNickname(snapshot.getSenderNickname())
                .content(snapshot.getContent())
                .messageType(snapshot.getMessageType())
                .sentAt(snapshot.getSentAt())
                .createdAt(snapshot.getCreatedAt())
                .build();
    }
}