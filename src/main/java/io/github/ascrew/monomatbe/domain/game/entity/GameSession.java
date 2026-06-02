package io.github.ascrew.monomatbe.domain.game.entity;

import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import jakarta.persistence.*;
import lombok.*;

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
}
