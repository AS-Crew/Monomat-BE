package io.github.ascrew.monomatbe.domain.game.entity;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Entity
@Builder
@Table(
    name = "GAME_SESSION_PLAYER",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_gsp_session_user",
        columnNames = {"game_session_id", "user_id"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GameSessionPlayer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_session_id", nullable = false)
    private GameSession gameSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "score", nullable = false)
    private Integer score;

    @PrePersist
    public void prePersist() {
        if (this.score == null) {
            this.score = 0;
        }
    }

    public void addScore(int points) {
        this.score += points;
    }
}
