package io.github.ascrew.monomatbe.domain.report.dto;

import java.util.List;

/**
 * 관리자 신고 목록 페이징 응답 DTO
 *
 * @param items   현재 페이지 신고 목록
 * @param page    0-based 현재 페이지 번호
 * @param size    요청한 페이지 크기
 * @param hasNext 다음 페이지 존재 여부
 */
public record AdminReportPageResponse(
        List<AdminReportListItemResponse> items,
        int page,
        int size,
        boolean hasNext
) {

    public AdminReportPageResponse {
        items = List.copyOf(items);
    }

    public static AdminReportPageResponse of(
            List<AdminReportListItemResponse> items,
            int page,
            int size,
            boolean hasNext
    ) {
        return new AdminReportPageResponse(
                items,
                page,
                size,
                hasNext
        );
    }
}