package io.github.ascrew.monomatbe.domain.report.entity;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 신고 엔티티
 *
 * [설계 의도]
 * 로비 신고와 로비 내 유저 신고를 하나의 report 테이블에서 관리한다.
 *
 * [targetType / targetId]
 * - LOBBY:
 *   - targetId = GAME_LOBBY.id
 *   - lobby = 신고 대상 로비
 *
 * - LOBBY_USER:
 *   - targetId = 신고 대상 users.id
 *   - lobby = 신고가 발생한 로비
 *
 * [lobby를 별도로 저장하는 이유]
 * 로비 내 유저 신고에서 targetId만 저장하면 어느 로비에서 발생한 신고인지 알 수 없다.
 * 운영자가 신고 맥락을 확인할 수 있도록 lobby를 별도 FK로 보관한다.
 *
 * [중복 신고 정책]
 * 동일 사용자가 동일 로비에서 동일 대상에 대해 PENDING 신고를 중복 생성하지 못하도록
 * 서비스 레이어와 Repository 조회 메서드에서 방어한다.
 */
@Getter
@Entity
@Builder
@Table(name = "report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 신고자
     * 게스트와 정식 회원 모두 users 테이블에 존재하므로 User FK로 통합 관리한다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /**
     * 신고가 발생한 로비
     *
     * 로비 자체 신고에서는 신고 대상 로비이고,
     * 로비 유저 신고에서는 해당 유저가 신고된 맥락의 로비이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lobby_id", nullable = false)
    private GameLobby lobby;

    /**
     * 신고 대상 타입
     *
     * LOBBY      : 로비 자체 신고
     * LOBBY_USER : 특정 로비 안의 유저 신고
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private ReportTargetType targetType;

    /**
     * 신고 대상 ID
     *
     * targetType에 따라 의미가 달라진다.
     * - LOBBY      : GAME_LOBBY.id
     * - LOBBY_USER : users.id
     */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /**
     * 신고 사유
     *
     * API 요청 DTO에서 @NotBlank, @Size로 1차 검증하고, 서비스 레이어에서 trim 정규화한 값을 저장한다.
     */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /**
     * 신고 처리 상태
     * 생성 직후 기본값은 PENDING이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    /**
     * 신고 접수 시각
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 운영자가 신고를 처리한 시각
     * PENDING 상태에서는 null이다.
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * 저장 직전 기본값을 보정한다.
     *
     * [기본값 정책]
     * status와 createdAt은 엔티티 내부에서만 기본값을 설정해
     * 서비스 레이어에 기본값 설정 책임이 퍼지지 않도록 한다.
     */
    @PrePersist
    public void prePersist() {
        if (this.status == null) {
            this.status = ReportStatus.PENDING;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 신고를 처리 완료 상태로 변경한다.
     *
     * 관리자 기능 구현 시 사용한다.
     */
    public void resolve() {
        this.status = ReportStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * 신고를 기각 상태로 변경한다.
     *
     * 관리자 기능 구현 시 사용한다.
     */
    public void dismiss() {
        this.status = ReportStatus.DISMISSED;
        this.resolvedAt = LocalDateTime.now();
    }
}