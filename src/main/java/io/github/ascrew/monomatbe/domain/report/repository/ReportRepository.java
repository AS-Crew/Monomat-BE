package io.github.ascrew.monomatbe.domain.report.repository;

import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * report 테이블 접근 JPA 리포지토리
 *
 * [주요 책임]
 * - 신고 저장
 * - 동일 사용자의 동일 대상 PENDING 중복 신고 여부 확인
 * - 신고 누적 카운트 조회
 * - 관리자 신고 목록/상세 조회
 *
 * [중복 신고 기준]
 * 동일 사용자가 같은 로비에서 같은 targetType/targetId에 대해
 * 아직 처리되지 않은 PENDING 신고를 이미 생성했다면 중복 신고로 본다.
 */
public interface ReportRepository extends JpaRepository<Report, Long> {

    /**
     * 동일 사용자의 동일 대상 미처리 신고 존재 여부를 확인한다.
     */
    boolean existsByReporterIdAndLobbyIdAndTargetTypeAndTargetIdAndStatus(
            Long reporterId,
            Long lobbyId,
            ReportTargetType targetType,
            Long targetId,
            ReportStatus status
    );

    /**
     * 특정 신고 대상의 미처리 신고 누적 수를 조회한다.
     */
    long countByTargetTypeAndTargetIdAndStatus(
            ReportTargetType targetType,
            Long targetId,
            ReportStatus status
    );

    /**
     * 특정 로비에서 발생한 미처리 신고 누적 수를 조회한다.
     */
    long countByLobbyIdAndStatus(
            Long lobbyId,
            ReportStatus status
    );

    /**
     * 관리자 신고 목록 조회.
     *
     * reporter와 lobby는 목록 응답에 필요하므로 EntityGraph로 함께 로딩한다.
     */
    @EntityGraph(attributePaths = {"reporter", "lobby"})
    Page<Report> findByTargetTypeAndStatus(
            ReportTargetType targetType,
            ReportStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"reporter", "lobby"})
    Page<Report> findByTargetType(
            ReportTargetType targetType,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"reporter", "lobby"})
    Page<Report> findByStatus(
            ReportStatus status,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"reporter", "lobby"})
    Page<Report> findAllBy(
            Pageable pageable
    );

    /**
     * 관리자 신고 상세 조회
     *
     * reporter와 lobby는 상세 응답에 필요하므로 fetch join으로 함께 조회한다.
     */
    @Query("""
            select report
            from Report report
            join fetch report.reporter
            join fetch report.lobby
            where report.id = :reportId
            """)
    Optional<Report> findDetailById(@Param("reportId") Long reportId);
}