package io.github.ascrew.monomatbe.domain.report.entity;

/**
 * 신고 대상 타입
 *
 * [설계 의도]
 * 신고 기능은 로비 자체, 로비 내 유저, 로비 채팅 메시지를 하나의 신고 도메인에서 관리한다.
 * targetType을 enum으로 분리해 문자열 하드코딩을 방지하고,
 * 후속 관리자/운영 기능에서 신고 대상을 타입별로 분기할 수 있도록 한다.
 */
public enum ReportTargetType {

    /**
     * 로비 자체 신고
     *
     * [targetId]
     * - game_lobby.id
     *
     * [예]
     * - 부적절한 로비 제목
     * - 부적절한 로비 설정
     * - 신고 누적 시 로비 자동 비공개 전환 후보
     */
    LOBBY,

    /**
     * 특정 로비 안의 유저 신고
     *
     * [targetId]
     * - users.id
     *
     * [context]
     * - 신고가 발생한 로비는 Report.lobby로 별도 보관한다.
     */
    LOBBY_USER,

    /**
     * 특정 로비 안의 채팅 메시지 신고
     *
     * [targetId]
     * - 채팅 메시지는 Redis messageId 기반이므로 Long targetId만으로 표현하기 어렵다.
     * - 이번 이슈에서는 후속 단계에서 채팅 메시지 신고 스냅샷 테이블을 추가하고,
     *   Report는 신고 공통 정보와 lobby context를 보관한다.
     *
     * [snapshot]
     * - Redis 최근 채팅은 TTL이 있으므로 신고 시점의 messageId, senderId,
     *   senderNickname, content, type, sentAt을 DB 스냅샷으로 별도 저장한다.
     */
    LOBBY_CHAT_MESSAGE
}