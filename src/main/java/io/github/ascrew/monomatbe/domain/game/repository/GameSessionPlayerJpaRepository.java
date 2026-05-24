package io.github.ascrew.monomatbe.domain.game.repository;

import io.github.ascrew.monomatbe.domain.game.entity.GameSessionPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameSessionPlayerJpaRepository extends JpaRepository<GameSessionPlayer, Long> {
    List<GameSessionPlayer> findAllByGameSessionId(Long gameSessionId);
}
