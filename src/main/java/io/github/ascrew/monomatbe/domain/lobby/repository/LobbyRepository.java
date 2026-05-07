/*
 * 로비 데이터에 접근하기 위한 Repository 인터페이스.
 * 구현체(LobbyRepositoryImpl)는 Redis와 직접 통신한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;

import java.util.List;

public interface LobbyRepository {

  /** 해당 코드의 로비가 Redis에 존재하는지 확인합니다. */
  boolean existsByCode(String code);

  /** 해당 유저가 해당 로비의 참여자인지 확인합니다. */
  boolean isParticipant(String code, String userId);

  /**
   * Redis에 로비 데이터를 저장하고 초대 코드를 반환한다.
   */
  String saveToRedis(CreateLobbyRequest request, String userIdentifier);

  /**
   * DB Insert 실패 시 Redis에 저장된 로비 데이터를 보상 삭제한다.
   *
   * [보상 삭제 대상]
   * - lobby:{code} Hash
   * - lobby:{code}:participants Set
   * - lobby:{code}:order List
   * - lobby:public Set에서 코드 제거
   * - lobby:code:lock:{code} 락 키
   *
   * 반환 타입을 void → boolean으로 변경
   * 보상 삭제 성공 여부를 서비스 레이어에서 확인하여
   * 실패 시 모니터링 가능한 로그/알림 처리를 가능하게 한다.
   *
   * @param inviteCode 삭제할 로비 초대 코드
   * @return 보상 삭제 성공 여부 (true: 성공, false: 실패)
   */
  boolean deleteFromRedis(String inviteCode);

  /**
   * Lua 스크립트를 실행하여 퇴장 처리를 원자적으로 수행한다.
   *
   * @return LeaveLobbyResult (Destroyed | Delegated | Left | Error)
   */
  LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId);

  /** Redis에서 공개 로비 목록을 필터링하여 반환한다. */
  List<LobbyRedisDto> getPublicLobbies();
}