package io.github.ascrew.monomatbe.repository;

import io.github.ascrew.monomatbe.dto.LobbyRedisDto;
import java.util.List;

public interface LobbyRepository {
  boolean existsByCode(String code);
  boolean isParticipant(String code, String userId);

  // [이슈 #19] 추가된 메서드들
  String executeLeaveLobbyProcess(String code, String userId);
  List<LobbyRedisDto> getPublicLobbies();
}