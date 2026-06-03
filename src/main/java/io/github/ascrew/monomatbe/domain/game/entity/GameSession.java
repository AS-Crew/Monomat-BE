package io.github.ascrew.monomatbe.domain.game.entity;

import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;

@Getter
@Entity
@Builder
@Table(name = "game_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GameSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lobby_id", nullable = false)
    private GameLobby lobby;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "map_id", nullable = false)
    private io.github.ascrew.monomatbe.domain.map.entity.QuizMap map;

    @Column(name = "current_round_no", nullable = false)
    private Integer currentRoundNo;

    @Column(name = "total_question_count", nullable = false)
    private Integer totalQuestionCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GameSessionStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.currentRoundNo == null) {
            this.currentRoundNo = 1;
        }
        if (this.status == null) {
            this.status = GameSessionStatus.READY;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public void finish() {
        this.status = GameSessionStatus.FINISHED;
        this.endedAt = java.time.LocalDateTime.now();
    }

    public void moveToNextRound(int targetRoundNo) {
        if (targetRoundNo != this.currentRoundNo + 1) {
            throw new IllegalStateException("다음 라운드로만 이동할 수 있습니다. current: " + this.currentRoundNo + ", target: " + targetRoundNo);
        }
        if (targetRoundNo > this.totalQuestionCount) {
            throw new IllegalStateException("최대 라운드 수를 초과할 수 없습니다. total: " + this.totalQuestionCount + ", target: " + targetRoundNo);
        }
        this.currentRoundNo = targetRoundNo;
    }

    /**
     * 미종료 세션이 임계 시간 이상 정체되어 stale(복구 대상)로 간주되는지 판별한다.
     *
     * {@code startedAt + threshold < now}이면 정상 진행 중으로 보기 어려운 정체 세션이다.
     * 새 게임 시작 시 이 조건을 만족하는 기존 active 세션만 강제 종료·복구하고,
     * 그렇지 않으면 진행 중 게임 보호를 위해 새 시작을 차단한다.
     *
     * @param now       현재 시각 (startedAt과 동일한 기준 zone — UTC)
     * @param threshold 정체 판별 임계 기간
     */
    public boolean isStale(LocalDateTime now, Duration threshold) {
        return startedAt.plus(threshold).isBefore(now);
    }
}
