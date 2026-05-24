package io.github.ascrew.monomatbe.domain.report.dto;

import lombok.Builder;

/**
 * 신고 누적 정책 판단 결과 DTO
 *
 * [사용 목적]
 * - 특정 로비의 PENDING 신고 수가 관리자 검토 임계값을 넘었는지 판단
 * - 향후 관리자 검토 큐 또는 자동 비공개 전환 정책에 연결
 *
 * [주의]
 * 이 DTO는 현재 단계에서 내부 서비스 판단 결과로 사용한다.
 * 외부 관리자 API는 후속 이슈에서 분리해 추가하는 편이 안전하다.
 */
@Builder
public record ReportModerationPolicyResponse(

        /**
         * 판단 대상 로비 ID
         */
        Long lobbyId,

        /**
         * 현재 PENDING 신고 수
         */
        long pendingReportCount,

        /**
         * 관리자 검토 대상이 되는 신고 임계값
         */
        int reviewThreshold,

        /**
         * 현재 신고 수가 관리자 검토 임계값 이상인지 여부
         */
        boolean reviewRequired,

        /**
         * 자동 비공개 전환 정책이 설정상 활성화되어 있는지 여부
         */
        boolean autoPrivateEnabled,

        /**
         * 현재 상태에서 자동 비공개 전환 후보인지 여부s
         *
         * 실제 비공개 전환을 수행했다는 뜻이 아니다.
         * autoPrivateEnabled=true 이고 reviewRequired=true일 때 true가 된다.
         */
        boolean autoPrivateCandidate
) {
}