package io.github.ascrew.monomatbe.domain.game.repository;

import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameSessionJpaRepository extends JpaRepository<GameSession, Long> {
    Optional<GameSession> findByLobbyId(Long lobbyId);
}
