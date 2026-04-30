package io.github.ascrew.monomatbe.lobby.repository;

import io.github.ascrew.monomatbe.lobby.domain.LeaveLobbyResult;
import io.github.ascrew.monomatbe.lobby.dto.LobbyRedisDto;
import java.util.List;

public interface LobbyRepository {
  boolean existsByCode(String code);
  boolean isParticipant(String code, String userId);

  // [이슈 #19] 추가된 메서드들
  LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId);
  List<LobbyRedisDto> getPublicLobbies();
}
