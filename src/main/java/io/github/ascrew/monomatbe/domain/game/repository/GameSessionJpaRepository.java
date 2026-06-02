package io.github.ascrew.monomatbe.domain.game.repository;

import io.github.ascrew.monomatbe.domain.game.entity.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameSessionJpaRepository extends JpaRepository<GameSession, Long> {
    Optional<GameSession> findTopByLobbyIdOrderByCreatedAtDesc(Long lobbyId);

    @org.springframework.data.jpa.repository.Query("select s from GameSession s where s.lobby.inviteCode = :inviteCode and s.status != io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus.FINISHED")
    Optional<GameSession> findActiveSessionByLobbyCode(@org.springframework.data.repository.query.Param("inviteCode") String inviteCode);

    java.util.List<GameSession> findAllByStatusNot(io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus status);

    @org.springframework.data.jpa.repository.Query("select s from GameSession s join fetch s.lobby where s.status != io.github.ascrew.monomatbe.domain.game.entity.GameSessionStatus.FINISHED")
    java.util.List<GameSession> findAllActiveSessionsWithLobby();
}
