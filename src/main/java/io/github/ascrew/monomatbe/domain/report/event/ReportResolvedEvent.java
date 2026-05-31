package io.github.ascrew.monomatbe.domain.report.event;

import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;

/**
 * 관리자가 신고를 유효 신고로 처리했을 때 발행되는 이벤트
 *
 * [목적]
 * 신고 승인과 실제 제재 처리를 느슨하게 분리한다.
 * 이번 PR에서는 이벤트 발행까지만 수행하고,
 * 계정 정지, 로비 폐쇄, 메시지 숨김 같은 실질 제재 listener는 후속 이슈에서 구현한다.
 *
 * [권장 listener 정책]
 * 후속 listener는 @TransactionalEventListener(phase = AFTER_COMMIT) 기반으로 처리한다.
 */
public record ReportResolvedEvent(
        Long reportId,
        Long reporterId,
        Long lobbyId,
        ReportTargetType targetType,
        Long targetId,
        String targetReference
) {
}