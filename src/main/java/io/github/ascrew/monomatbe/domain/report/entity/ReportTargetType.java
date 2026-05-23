package io.github.ascrew.monomatbe.domain.report.entity;

/**
 * 신고 대상 타입
 *
 * [설계 의도]
 * 신고 기능은 현재 로비 신고와 로비 내 유저 신고를 우선 지원한다.
 * 이후 맵, 채팅 메시지, 일반 유저 신고로 확장될 수 있으므로
 * targetType을 enum으로 분리해 문자열 하드코딩을 방지한다.
 */
public enum ReportTargetType {

    /**
     * 로비 자체 신고
     *
     * 예:
     * - 부적절한 로비 제목
     * - 부적절한 로비 설정
     * - 신고 누적 시 로비 자동 비공개 전환 후보
     */
    LOBBY,

    /**
     * 특정 로비 안의 유저 신고
     *
     * targetId는 신고 대상 user.id를 의미하고,
     * 신고가 발생한 로비는 Report.lobby로 별도 보관한다.
     */
    LOBBY_USER
}