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
 * 로비 신고, 로비 내 유저 신고, 로비 채팅 메시지 신고를 하나의 report 테이블에서 관리한다.
 *
 * [targetType / targetId / targetReference]
 * - LOBBY:
 *   - targetId = GAME_LOBBY.id
 *   - targetReference = null
 *   - lobby = 신고 대상 로비
 *
 * - LOBBY_USER:
 *   - targetId = 신고 대상 users.id
 *   - targetReference = null
 *   - lobby = 신고가 발생한 로비
 *
 * - LOBBY_CHAT_MESSAGE:
 *   - targetId = 신고가 발생한 GAME_LOBBY.id
 *   - targetReference = Redis 최근 채팅 messageId
 *   - lobby = 신고가 발생한 로비
 *   - 상세 원문은 LobbyChatMessageReportSnapshot에 저장한다.
 *
 * [lobby를 별도로 저장하는 이유]
 * 로비 내 유저 신고와 채팅 메시지 신고에서 targetId/targetReference만 저장하면
 * 어느 로비에서 발생한 신고인지 알기 어렵다.
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

    private static final int TARGET_TYPE_MAX_LENGTH = 30;
    private static final int TARGET_REFERENCE_MAX_LENGTH = 100;
    private static final int REASON_MAX_LENGTH = 500;
    private static final int STATUS_MAX_LENGTH = 20;

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
     * 로비 유저/채팅 메시지 신고에서는 신고가 발생한 맥락의 로비이다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lobby_id", nullable = false)
    private GameLobby lobby;

    /**
     * 신고 대상 타입
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = TARGET_TYPE_MAX_LENGTH)
    private ReportTargetType targetType;

    /**
     * 신고 대상 숫자 ID
     *
     * targetType에 따라 의미가 달라진다.
     * - LOBBY              : GAME_LOBBY.id
     * - LOBBY_USER         : users.id
     * - LOBBY_CHAT_MESSAGE : 신고가 발생한 GAME_LOBBY.id
     */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /**
     * 신고 대상 문자열 식별자
     *
     * [사용 예]
     * - LOBBY_CHAT_MESSAGE: Redis 최근 채팅 messageId
     *
     * 숫자 ID로 표현할 수 없는 신고 대상 식별자를 보관한다.
     */
    @Column(name = "target_reference", length = TARGET_REFERENCE_MAX_LENGTH)
    private String targetReference;

    /**
     * 신고 사유
     *
     * API 요청 DTO에서 @NotBlank, @Size로 1차 검증하고,
     * 서비스 레이어에서 trim 정규화한 값을 저장한다.
     */
    @Column(name = "reason", nullable = false, length = REASON_MAX_LENGTH)
    private String reason;

    /**
     * 신고 처리 상태
     * 생성 직후 기본값은 PENDING이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
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
     */
    public void resolve() {
        this.status = ReportStatus.RESOLVED;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * 신고를 기각 상태로 변경한다.
     */
    public void dismiss() {
        this.status = ReportStatus.DISMISSED;
        this.resolvedAt = LocalDateTime.now();
    }
}