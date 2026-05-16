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
 *
 * [기본값 정책 — @PrePersist 단일 책임]
 * status, isDeleted, createdAt의 기본값은 @PrePersist에서만 설정한다.
 * LobbyCreateService는 이 필드들을 Builder에서 명시하지 않으며,
 * @PrePersist가 유일한 기본값 설정 지점이다.
 * 이를 통해 기본값 로직이 분산되지 않고 엔티티에 캡슐화된다.
 */
@Getter
@Entity
@Builder
@Table(name = "GAME_LOBBY")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GameLobby {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_user_id", nullable = false)
    private User host;

    @Column(name = "map_id")
    private Long mapId;

    /**
     * 로비 초대 코드.
     *
     * [length 설정]
     * 초대 코드는 LobbyDefaults.INVITE_CODE_LENGTH 상수 기준 6자리
     * @Column의 length는 컴파일 타임 상수만 허용하므로 직접 참조할 수 없다.
     * LobbyDefaults.INVITE_CODE_LENGTH 값과 반드시 동기화되어야 한다.
     */
    @Column(name = "invite_code", nullable = false, unique = true, length = 6)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LobbyStatus status;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * DB 저장 전 기본값을 보정한다.
     *
     * [단일 책임 원칙 — 기본값 설정의 유일한 지점]
     * status, isDeleted, createdAt의 기본값은 이 메서드에서만 설정한다.
     * 서비스 레이어에서 이 필드들을 Builder로 명시하지 않는 것이 의도된 설계이다.
     *
     * [보정 항목]
     * - createdAt : Builder에서 누락 시 자동 세팅
     * - status    : 로비 생성 시 항상 WAITING이 초기값
     * - isDeleted : 생성 시 삭제 상태가 아님
     */
    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = LobbyStatus.WAITING;
        }
        if (this.isDeleted == null) {
            this.isDeleted = false;
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