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
   *
   * [SETNX 기반 초대 코드 중복 방지]
   * 코드 생성 → SETNX 선점 → 실패 시 재시도 로직을 포함한다.
   * 최대 재시도 횟수 초과 시 예외를 던진다.
   */
  String saveToRedis(CreateLobbyRequest request, String userIdentifier);

  /**
   * Lua 스크립트를 실행하여 퇴장 처리를 원자적으로 수행합니다.
   *
   * [반환 타입 변경 이유]
   * 기존 String 반환 방식은 서비스 레이어에서 문자열 파싱을 해야 했습니다.
   * LeaveLobbyResult sealed interface로 변경하여 파싱 책임을 Repository로 캡슐화하고
   * 서비스 레이어는 순수한 도메인 결과만 받도록 개선합니다.
   *
   * @return LeaveLobbyResult (Destroyed | Delegated | Left | Error)
   */
  LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId);

  /** Redis에서 공개 로비 목록을 필터링하여 반환합니다. */
  List<LobbyRedisDto> getPublicLobbies();
}