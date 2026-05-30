/*
 * 로비 데이터에 접근하기 위한 Repository 인터페이스.
 * 구현체(LobbyRepositoryImpl)는 Redis와 직접 통신한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.LobbyMapCompensationResult;
import io.github.ascrew.monomatbe.domain.lobby.LobbyUserAccessStatus;
import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LobbyRepository {

  /** 해당 코드의 로비가 Redis에 존재하는지 확인합니다. */
  boolean existsByCode(String code);

  /** 해당 유저가 해당 로비의 참여자인지 확인합니다. */
  boolean isParticipant(String code, String userId);

  /** 해당 유저가 해당 로비에서 강퇴된 유저인지 확인합니다. */
  boolean isKicked(String code, String userIdentifier);

  /**
   * 로비 사용자 접근 상태를 조회한다.
   *
   * <p>로비 존재 여부, 강퇴 여부, 참여 여부를 한 번의 Repository 호출로 판별하기 위한 메서드다.
   * 구현체는 Redis pipeline 또는 Lua 등을 사용해 네트워크 I/O를 줄일 수 있다.
   *
   * @param code 로비 초대 코드
   * @param userIdentifier 사용자 식별자
   * @return 로비 사용자 접근 상태
   */
  LobbyUserAccessStatus getUserAccessStatus(String code, String userIdentifier);

  /**
   * 로비 참여자의 준비 상태를 변경한다.
   *
   * @param code 로비 초대 코드
   * @param userIdentifier 준비 상태를 변경할 사용자 식별자
   * @param ready true면 준비 완료, false면 준비 해제
   */
  void updateReadyStatus(String code, String userIdentifier, boolean ready);

  /**
   * 로비 참여자 목록을 입장 순서를 기준으로 조회한다.
   *
   * @param code 로비 초대 코드
   * @return 입장 순서가 반영된 userIdentifier 목록
   */
  List<String> getParticipantIdentifiers(String code);

  /**
   * 로비에서 ready 상태인 참여자 식별자 목록을 조회한다.
   *
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
   * @param effectiveQuestionCount 실제 적용할 문제 수
   * @param timeLimitSeconds 제한 시간(초)
   */
  String saveToRedis(
          CreateLobbyRequest request,
          String userIdentifier,
          LobbyMapMetadata mapMetadata,
          int effectiveQuestionCount,
          int timeLimitSeconds
  );

  /**
   * DB Insert 실패 시 Redis에 저장된 로비 데이터를 보상 삭제한다.
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

  /**
   * Lua 스크립트를 실행하여 로비 게임 시작 조건을 원자적으로 검증하고
   * Redis 로비 상태를 PLAYING으로 변경한다.
   *
   * @param code 로비 초대 코드
   * @param requesterIdentifier 게임 시작 요청자 식별자
   * @return StartLobbyResult
   */
  StartLobbyResult executeStartLobbyProcess(
          String code,
          String requesterIdentifier
  );

  /**
   * 게임 시작 처리 DB 상태 변경 실패가 발생했을 때, Redis 로비 상태를 WAITING으로 보상 롤백한다.
   *
   * @param code 로비 초대 코드
   * @return Redis 보상 롤백 성공 여부
   */
  boolean rollbackStartedLobbyStatus(String code);

  /**
   * Redis-DB 상태 불일치가 발생했을 때 후속 재처리를 위해 Redis 큐에 적재한다.
   *
   * @param code 로비 초대 코드
   * @param reason 재처리 사유
   */
  void enqueueStartReconciliation(String code, String reason);

  /**
   * Redis-DB 상태 불일치 재처리 큐에서 payload를 하나 꺼낸다.
   *
   * @return "lobbyCode|reason" 형태의 payload. 없으면 null.
   */
  String pollStartReconciliation();

  /**
   * 재처리에 실패한 payload를 큐에 다시 적재한다.
   *
   * @param payload 재처리 payload
   */
  void requeueStartReconciliation(String payload);

  /**
   * 게임 시작 상태 재처리 metric counter를 증가시킨다.
   *
   * @param metricKey Redis metric key
   */
  void incrementStartReconciliationMetric(String metricKey);

  /** Redis에서 공개 로비 목록을 필터링하여 반환한다. */
  List<LobbyRedisDto> getPublicLobbies();

  /**
   * 공개 로비 stale index 정리용으로 lobby:public Set의 일부 로비 코드를 조회한다.
   *
   * [사용 목적]
   * 조회 API 경로에서 stale index를 즉시 삭제하지 않고,
   * 스케줄러가 제한된 개수만 점진적으로 정리하기 위해 사용한다.
   *
   * @param limit 조회할 최대 code 수
   * @return 정리 후보 로비 코드 목록
   */
  List<String> getPublicLobbyCodesForCleanup(int limit);

  /**
   * 공개 로비 최신순 ZSET 인덱스 존재 여부를 확인한다.
   *
   * @return lobby:public:latest 존재 여부
   */
  boolean existsPublicLatestIndex();

  /**
   * 공개 로비 현재 인원 많은 순 ZSET 인덱스 존재 여부를 확인한다.
   *
   * @return lobby:public:most_players 존재 여부
   */
  boolean existsPublicMostPlayersIndex();

  /**
   * 공개 로비 빈자리 많은 순 ZSET 인덱스 존재 여부를 확인한다.
   *
   * @return lobby:public:most_available 존재 여부
   */
  boolean existsPublicMostAvailableIndex();

  /**
   * 공개 로비 최신순 ZSET 인덱스에서 필요한 범위의 로비 코드만 조회한다.
   *
   * @param offset 0-based 조회 시작 offset
   * @param limit 조회 개수
   * @return 최신순 로비 코드 목록
   */
  List<String> getPublicLobbyCodesByLatestIndex(long offset, int limit);

  /**
   * 공개 로비 현재 인원 많은 순 ZSET 인덱스에서 필요한 범위의 로비 코드만 조회한다.
   *
   * @param offset 0-based 조회 시작 offset
   * @param limit 조회 개수
   * @return 현재 인원 많은 순 로비 코드 목록
   */
  List<String> getPublicLobbyCodesByMostPlayersIndex(long offset, int limit);

  /**
   * 공개 로비 빈자리 많은 순 ZSET 인덱스에서 필요한 범위의 로비 코드만 조회한다.
   *
   * @param offset 0-based 조회 시작 offset
   * @param limit 조회 개수
   * @return 빈자리 많은 순 로비 코드 목록
   */
  List<String> getPublicLobbyCodesByMostAvailableIndex(long offset, int limit);

  /**
   * 주어진 로비 코드 목록에 대해서만 공개 로비 DTO를 조회한다.
   *
   * @param lobbyCodes 조회할 로비 코드 목록
   * @return 조회 가능한 공개 로비 DTO 목록
   */
  List<LobbyRedisDto> getPublicLobbiesByCodes(List<String> lobbyCodes);

  /**
   * 공개 로비 인덱스에서 특정 로비 코드를 제거한다.
   *
   * [정리 대상]
   * - lobby:public
   * - lobby:public:latest
   * - lobby:public:most_players
   * - lobby:public:most_available
   *
   * @param lobbyCode 제거할 로비 코드
   */
  void removePublicLobbyIndexes(String lobbyCode);

  /**
   * 초대 코드로 로비 입장에 필요한 정보를 조회한다.
   *
   * [반환 전략]
   * 로비가 존재하지 않으면 Optional.empty()를 반환한다.
   * 서비스 레이어에서 empty 여부로 404를 처리하므로, Repository는 존재 여부 판단을 서비스에 위임한다.
   *
   * @param inviteCode 로비 초대 코드
   * @return 로비 정보 Optional (로비 미존재 시 empty)
   */
  Optional<JoinLobbyResponse> findByInviteCode(String inviteCode);

  /**
   * 해당 로비의 현재 참여 인원 수를 반환한다.
   *
   * @param inviteCode 로비 초대 코드
   * @return 현재 참여 인원 수
   */
  int getCurrentPlayerCount(String inviteCode);

  /**
   * Redis 로비 Hash의 맵 메타데이터와 문제 수를 갱신한다.
   *
   * @param code          로비 초대 코드
   * @param metadata      새 맵 메타데이터 (null이면 맵 필드를 제거한다)
   * @param questionCount 새 문제 수
   */
  void updateMapMetadata(String code, LobbyMapMetadata metadata, int questionCount);

  /**
   * 로비 맵 변경 트랜잭션 보상 복구를 status==WAITING 원자 검증과 함께 수행한다.
   *
   * [필요 이유]
   * 보상 시점에 다른 트랜잭션이 status를 PLAYING으로 바꿨다면 oldMetadata로 되돌리면 안 된다.
   * Java에서 status 조회 후 분기하면 race window가 남으므로 compensate_lobby_map.lua로 원자 처리한다.
   *
   * [예외 정책]
   * Redis 연결 단절·타임아웃·Lua 스크립트 로딩 실패 등 인프라 예외는 호출자에게 그대로 전파한다.
   * 호출자(LobbyMapUpdateService)가 도메인 결과(LOBBY_NOT_FOUND)와 인프라 장애를 분리 처리한다.
   *
   * @param code             로비 초대 코드
   * @param oldMetadata      복구할 이전 맵 메타데이터 (null 또는 필드가 null이면 HDEL 처리)
   * @param oldQuestionCount 복구할 이전 문제 수
   * @return 보상 처리 결과 (COMPENSATED / SKIPPED_NOT_WAITING / LOBBY_NOT_FOUND)
   * @throws RuntimeException Redis 인프라 오류 발생 시
   */
  LobbyMapCompensationResult compensateMapMetadataIfWaiting(
          String code,
          LobbyMapMetadata oldMetadata,
          int oldQuestionCount
  );
}