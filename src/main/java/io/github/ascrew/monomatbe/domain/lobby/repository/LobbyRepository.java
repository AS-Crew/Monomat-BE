/*
 * 로비 데이터에 접근하기 위한 Repository 인터페이스.
 * 구현체(LobbyRepositoryImpl)는 Redis와 직접 통신한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;

import java.util.List;

public interface LobbyRepository {

  /** 해당 코드의 로비가 Redis에 존재하는지 확인합니다. */
  boolean existsByCode(String code);

  /** 해당 유저가 해당 로비의 참여자인지 확인합니다. */
  boolean isParticipant(String code, String userId);

  /**
   * Lua 스크립트를 실행하여 퇴장 처리를 원자적으로 수행합니다.
   * 반환값: "DESTROYED" | "DELEGATED:{newHostId}" | "LEFT"
   */
  String executeLeaveLobbyProcess(String code, String userId);

  /** Redis에서 공개 로비 목록을 필터링하여 반환합니다. */
  List<LobbyRedisDto> getPublicLobbies();
}