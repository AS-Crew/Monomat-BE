/*
 * 로비 데이터에 접근하기 위한 Repository 인터페이스.
 * 구현체(LobbyRepositoryImpl)는 Redis와 직접 통신한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.*;
import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LobbyRepository {

  /** 해당 코드의 로비가 Redis에 존재하는지 확인합니다. */
  boolean existsByCode(String code);

  /** 해당 유저가 해당 로비의 참여자인지 확인합니다. */
  boolean isParticipant(String code, String userId);

  /**
   * 로비 참여자의 준비 상태를 변경한다.
   * @param code 로비 초대 코드
   * @param userIdentifier 준비 상태를 변경할 사용자 식별자
   * @param ready true면 준비 완료, false면 준비 해제
   */
  void updateReadyStatus(String code, String userIdentifier, boolean ready);

  /**
   * 로비 참여자 목록을 입장 순서를 기준으로 조회한다.
   * @param code 로비 초대 코드
   * @return 입장 순서가 반영된 userIdentifier 목록
   */
  List<String> getParticipantIdentifiers(String code);

  /**
   * 로비에서 ready 상태인 참여자 식별자 목록을 조회한다.
   * @param code 로비 초대 코드
   * @return ready Set에 저장된 userIdentifier 목록
   */
  Set<String> getReadyParticipantIdentifiers(String code);

  /**
   * Redis에 로비 데이터를 저장하고 초대 코드를 반환한다.
   *
   * @param request 로비 생성 요청
   * @param userIdentifier 방장 사용자 식별자
   * @param mapMetadata 선택된 맵 메타데이터 (맵 미선택 시 null)
   */
  String saveToRedis(
          CreateLobbyRequest request,
          String userIdentifier,
          LobbyMapMetadata mapMetadata
  );

  /**
   * DB Insert 실패 시 Redis에 저장된 로비 데이터를 보상 삭제한다.
   * @param inviteCode 삭제할 로비 초대 코드
   * @return 보상 삭제 성공 여부 (true: 성공, false: 실패)
   */
  boolean deleteFromRedis(String inviteCode);

  /**
   * Lua 스크립트를 실행하여 퇴장 처리를 원자적으로 수행한다.
   * @return LeaveLobbyResult (Destroyed | Delegated | Left | Error)
   */
  LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId);

  /**
   * Lua 스크립트를 실행하여 방장의 로비 유저 강퇴를 원자적으로 수행한다.
   *
   * @param code 로비 초대 코드
   * @param requesterIdentifier 강퇴 요청자 식별자
   * @param targetUserIdentifier 강퇴 대상 식별자
   * @return KickLobbyResult
   */
  KickLobbyResult executeKickLobbyProcess(
          String code,
          String requesterIdentifier,
          String targetUserIdentifier
  );

  /** Redis에서 공개 로비 목록을 필터링하여 반환한다. */
  List<LobbyRedisDto> getPublicLobbies();

  /**
   * 초대 코드로 로비 입장에 필요한 정보를 조회한다.
   *
   * [반환 전략]
   * 로비가 존재하지 않으면 Optional.empty()를 반환한다.
   * 서비스 레이어에서 empty 여부로 404를 처리 하므로, Repository는 존재 여부 판단을 서비스에 위임한다.
   *
   * @param inviteCode 로비 초대 코드
   * @return 로비 정보 Optional (로비 미존재 시 empty)
   */
  Optional<JoinLobbyResponse> findByInviteCode(String inviteCode);

  /**
   * 해당 로비의 현재 참여 인원 수를 반환한다.
   * @param inviteCode 로비 초대 코드
   * @return 현재 참여 인원 수
   */
  int getCurrentPlayerCount(String inviteCode);
}