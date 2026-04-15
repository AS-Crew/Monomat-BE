package io.github.ascrew.monomatbe.repository;

public interface LobbyRepository {
  boolean existsByCode(String code);
  boolean isParticipant(String code, String userId);
}