package io.github.ascrew.monomatbe.repository;

public interface LobbyRepository {
  boolean existsById(String id);
  boolean isParticipant(String code, String userId);
}