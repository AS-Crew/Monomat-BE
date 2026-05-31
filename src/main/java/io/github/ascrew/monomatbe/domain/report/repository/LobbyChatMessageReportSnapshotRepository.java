package io.github.ascrew.monomatbe.domain.report.repository;

import io.github.ascrew.monomatbe.domain.report.entity.LobbyChatMessageReportSnapshot;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 로비 채팅 메시지 신고 스냅샷 Repository
 *
 * [책임]
 * - 채팅 메시지 신고 스냅샷 저장
 * - report와 연결된 스냅샷 조회
 * - 동일 사용자의 동일 메시지 PENDING 신고 중복 여부 확인
 */
public interface LobbyChatMessageReportSnapshotRepository
        extends JpaRepository<LobbyChatMessageReportSnapshot, Long> {

    /**
     * 특정 report에 연결된 채팅 메시지 스냅샷 존재 여부를 확인한다.
     *
     * @param reportId report.id
     * @return 존재 여부
     */
    boolean existsByReportId(Long reportId);

    /**
     * 동일 사용자가 동일 로비에서 동일 채팅 메시지를 이미 PENDING 상태로 신고했는지 확인한다.
     *
     * [중복 신고 기준]
     * - reporterId 동일
     * - lobbyId 동일
     * - targetType = LOBBY_CHAT_MESSAGE
     * - messageId 동일
     * - status = PENDING
     *
     * [설계 이유]
     * 채팅 메시지의 messageId는 String이므로 기존 Report.targetId(Long) 기반 중복 검증에 태우지 않는다.
     * 스냅샷 테이블의 message_id와 report 테이블을 join해 중복 여부를 판단한다.
     */
    @Query("""
            select count(snapshot) > 0
            from LobbyChatMessageReportSnapshot snapshot
            join snapshot.report report
            where report.reporter.id = :reporterId
              and report.lobby.id = :lobbyId
              and report.targetType = :targetType
              and report.status = :status
              and snapshot.messageId = :messageId
            """)
    boolean existsPendingReportByReporterAndLobbyAndMessageId(
            @Param("reporterId") Long reporterId,
            @Param("lobbyId") Long lobbyId,
            @Param("messageId") String messageId,
            @Param("targetType") ReportTargetType targetType,
            @Param("status") ReportStatus status
    );
}