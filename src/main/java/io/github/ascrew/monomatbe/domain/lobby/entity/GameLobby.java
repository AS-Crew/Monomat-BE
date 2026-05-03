package io.github.ascrew.monomatbe.domain.lobby.entity;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
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
 * 게임 로비 엔티티.
 *
 * [설계 의도 — ARCHITECTURE.md 기준]
 * 실시간 로비 상태는 Redis에서 관리한다.
 * 이 테이블은 아래 두 가지 목적으로만 사용된다.
 *   1. 로비 생성 시점의 스냅샷 영속화 (장애 복구 대비)
 *   2. 신고(report) 기능의 target_id 레퍼런스
 *
 * [Soft Delete]
 * 로비 폭파 시 레코드를 물리 삭제하지 않고 is_deleted = true로 처리한다.
 * 신고 이력 추적 및 운영 감사 로그 보존을 위해 map, map_item과 동일한 정책을 적용한다.
 *
 * [host_user_id]
 * Redis의 host_user_id(UUID String)와 달리 DB에는 users.id(Long)를 FK로 저장한다.
 * 두 식별자의 역할 분리는 ARCHITECTURE.md 인증 구조를 따른다.
 */
@Getter
@Entity
@Builder // 객체 생성 시 가독성과 유연성을 높이기 위해 빌더 패턴을 적용
@Table(name = "GAME_LOBBY") // 데이터베이스에 생성될 테이블 이름
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GameLobby {

    @Id // 이 필드가 테이블의 기본 키 (Primary Key)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 방장 (User) 정보와의 다대일 (N:1) 연관관계 매핑
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_user_id", nullable = false)
    private User host;

    @Column(name = "map_id")
    private Long mapId;

    @Column(name = "invite_code", nullable = false, unique = true, length = 12)
    private String inviteCode;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "max_players", nullable = false)
    private Integer maxPlayers;

    @Column(name = "round_count", nullable = false)
    private Integer roundCount;

    @Column(name = "time_limit_seconds", nullable = false)
    private Integer timeLimitSeconds;

    @Column(name = "is_private", nullable = false)
    private Boolean isPrivate;

    // 로비 현재 상태 (예 : WAITING, PLAYING 등)
    // EnumType.STRING : Enum의 순서 (숫자)가 아닌 문자열 이름 자체를 DB에 저장하여 데이터 정합성을 보호한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LobbyStatus status;

    // 로비가 파괴되었는지 여부를 나타내는 논리적 삭제 (Soft Delete) 플래그
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    // 로비 생성 시간
    // 한 번 생성된 이후에는 수정되지 않도록 막아 데이터 불변성을 보장한다.
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * DB 저장 전 기본값을 보정한다.
     *
     * [보정 항목]
     * - createdAt : 서비스 레이어에서 누락 시 자동 세팅 (User, GuestSession과 동일한 패턴)
     * - status    : 로비 생성 시 항상 WAITING이 초기값
     * - isDeleted : 생성 시 삭제 상태가 아님
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = LobbyStatus.WAITING; // 초기 상태는 항상 '대기 중'
        }
        if (this.isDeleted == null) {
            this.isDeleted = false; // 방금 생성했으므로 삭제되지 않은 상태
        }
    }

    /**
     * 로비 폭파 시 Soft Delete 처리한다.
     * Redis에서 로비가 제거된 이후 DB 이력 보존을 위해 사용한다.
     */
    public void delete() {
        this.isDeleted = true;
    }

    /**
     * 로비 상태를 변경한다.
     * WAITING → PLAYING 전이 시 사용한다.
     */
    public void changeStatus(LobbyStatus status) {
        this.status = status;
    }
}