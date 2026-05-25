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

    @Column(name = "current_round_no", nullable = false)
    private Integer currentRoundNo;

    @Column(name = "total_round_count", nullable = false)
    private Integer totalRoundCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GameSessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.currentRoundNo == null) {
            this.currentRoundNo = 1;
        }
        if (this.status == null) {
            this.status = GameSessionStatus.PLAYING;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public void finish() {
        this.status = GameSessionStatus.FINISHED;
    }

    public void nextRound() {
        this.currentRoundNo++;
    }
}
