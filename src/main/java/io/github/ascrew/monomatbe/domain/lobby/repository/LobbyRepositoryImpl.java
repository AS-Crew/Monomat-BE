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
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyInviteCodeGenerator;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyLuaResultMapper;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyLuaScriptExecutor;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyRedisCommandRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyRedisQueryRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyStartReconciliationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 로비 Redis Repository 구현체
 *
 * [역할]
 * 이 클래스는 기존 LobbyRepository 인터페이스 계약을 유지하는 Facade다.
 * Redis 접근 세부 책임은 역할별 하위 컴포넌트로 위임한다.
 *
 * [분리된 책임]
 * - 초대 코드 생성: LobbyInviteCodeGenerator
 * - Lua 실행: LobbyLuaScriptExecutor
 * - Lua 결과 매핑: LobbyLuaResultMapper
 * - Redis 조회: LobbyRedisQueryRepository
 * - Redis command: LobbyRedisCommandRepository
 * - start reconciliation queue: LobbyStartReconciliationRepository
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private static final String CREATE_LOBBY_RESULT_OK = "OK";
  private static final String CREATE_LOBBY_RESULT_LOCK_FAILED = "LOCK_FAILED";

  private static final String ERROR_INVITE_CODE_EXHAUSTED =
          "초대 코드 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

  private static final String ERROR_REDIS_SCRIPT_NULL =
          "Redis 스크립트 실행 결과가 null입니다. Redis 연결 상태를 확인해주세요.";

  private static final String ERROR_REDIS_SCRIPT_UNKNOWN_RESULT =
          "Redis 로비 생성 처리 결과가 유효하지 않습니다.";

  private final LobbyInviteCodeGenerator lobbyInviteCodeGenerator;
  private final LobbyLuaScriptExecutor lobbyLuaScriptExecutor;
  private final LobbyLuaResultMapper lobbyLuaResultMapper;
  private final LobbyRedisQueryRepository lobbyRedisQueryRepository;
  private final LobbyRedisCommandRepository lobbyRedisCommandRepository;
  private final LobbyStartReconciliationRepository lobbyStartReconciliationRepository;

  // =========================================================
  // Redis Query
  // =========================================================

  @Override
  public boolean existsByCode(String code) {
    return lobbyRedisQueryRepository.existsByCode(code);
  }

  @Override
  public boolean isParticipant(String code, String userId) {
    return lobbyRedisQueryRepository.isParticipant(code, userId);
  }

  @Override
  public boolean isKicked(String code, String userIdentifier) {
    return lobbyRedisQueryRepository.isKicked(code, userIdentifier);
  }

  @Override
  public LobbyUserAccessStatus getUserAccessStatus(String code, String userIdentifier) {
    return lobbyRedisQueryRepository.getUserAccessStatus(code, userIdentifier);
  }

  @Override
  public List<String> getParticipantIdentifiers(String code) {
    return lobbyRedisQueryRepository.getParticipantIdentifiers(code);
  }

  @Override
  public Set<String> getReadyParticipantIdentifiers(String code) {
    return lobbyRedisQueryRepository.getReadyParticipantIdentifiers(code);
  }

  @Override
  public List<LobbyRedisDto> getPublicLobbies() {
    return lobbyRedisQueryRepository.getPublicLobbies();
  }

  @Override
  public boolean existsPublicLatestIndex() {
    return lobbyRedisQueryRepository.existsPublicLatestIndex();
  }

  @Override
  public boolean existsPublicMostPlayersIndex() {
    return lobbyRedisQueryRepository.existsPublicMostPlayersIndex();
  }

  @Override
  public boolean existsPublicMostAvailableIndex() {
    return lobbyRedisQueryRepository.existsPublicMostAvailableIndex();
  }

  @Override
  public List<String> getPublicLobbyCodesByLatestIndex(long offset, int limit) {
    return lobbyRedisQueryRepository.getPublicLobbyCodesByLatestIndex(offset, limit);
  }

  @Override
  public List<String> getPublicLobbyCodesByMostPlayersIndex(long offset, int limit) {
    return lobbyRedisQueryRepository.getPublicLobbyCodesByMostPlayersIndex(offset, limit);
  }

  @Override
  public List<String> getPublicLobbyCodesByMostAvailableIndex(long offset, int limit) {
    return lobbyRedisQueryRepository.getPublicLobbyCodesByMostAvailableIndex(offset, limit);
  }

  @Override
  public List<LobbyRedisDto> getPublicLobbiesByCodes(List<String> lobbyCodes) {
    return lobbyRedisQueryRepository.getPublicLobbiesByCodes(lobbyCodes);
  }

  @Override
  public void removePublicLobbyIndexes(String lobbyCode) {
    lobbyRedisQueryRepository.removePublicLobbyIndexes(lobbyCode);
  }

  @Override
  public Optional<JoinLobbyResponse> findByInviteCode(String inviteCode) {
    return lobbyRedisQueryRepository.findByInviteCode(inviteCode);
  }

  @Override
  public int getCurrentPlayerCount(String inviteCode) {
    return lobbyRedisQueryRepository.getCurrentPlayerCount(inviteCode);
  }

  // =========================================================
  // Redis Command
  // =========================================================

  @Override
  public void updateReadyStatus(String code, String userIdentifier, boolean ready) {
    lobbyRedisCommandRepository.updateReadyStatus(code, userIdentifier, ready);
  }

  @Override
  public boolean deleteFromRedis(String inviteCode) {
    return lobbyRedisCommandRepository.deleteFromRedis(inviteCode);
  }

  @Override
  public boolean rollbackStartedLobbyStatus(String code) {
    return lobbyRedisCommandRepository.rollbackStartedLobbyStatus(code);
  }

  @Override
  public void updateMapMetadata(String code, LobbyMapMetadata metadata, int questionCount) {
    lobbyRedisCommandRepository.updateMapMetadata(code, metadata, questionCount);
  }

  @Override
  public LobbyMapCompensationResult compensateMapMetadataIfWaiting(
          String code,
          LobbyMapMetadata oldMetadata,
          int oldQuestionCount
  ) {
    String result = lobbyLuaScriptExecutor.executeCompensateLobbyMap(code, oldMetadata, oldQuestionCount);
    return lobbyLuaResultMapper.toLobbyMapCompensationResult(result, code);
  }

  // =========================================================
  // Lua-backed State Transition
  // =========================================================

  @Override
  public String saveToRedis(
          CreateLobbyRequest request,
          String userIdentifier,
          LobbyMapMetadata mapMetadata,
          int effectiveQuestionCount,
          int timeLimitSeconds
  ) {
    for (int attempt = 0; attempt < LobbyDefaults.INVITE_CODE_MAX_RETRY; attempt++) {
      String candidate = lobbyInviteCodeGenerator.generate();

      String result = lobbyLuaScriptExecutor.executeCreateLobby(
              candidate,
              request,
              userIdentifier,
              mapMetadata,
              effectiveQuestionCount,
              timeLimitSeconds
      );

      if (result == null) {
        log.error("Lua 스크립트 null 반환 - Redis 연결 오류 가능성. code: {}", candidate);

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ERROR_REDIS_SCRIPT_NULL
        );
      }

      if (CREATE_LOBBY_RESULT_OK.equals(result)) {
        log.info(
                "로비 Redis 저장 완료 - code: {}, host: {}, mapId: {}",
                candidate,
                userIdentifier,
                mapMetadata != null ? mapMetadata.mapId() : null
        );

        return candidate;
      }

      if (CREATE_LOBBY_RESULT_LOCK_FAILED.equals(result)) {
        log.warn(
                "초대 코드 충돌 - retry: {}/{}, code: {}",
                attempt + 1,
                LobbyDefaults.INVITE_CODE_MAX_RETRY,
                candidate
        );

        continue;
      }

      log.error(
              "create_lobby.lua 알 수 없는 반환값 - code: {}, result: {}",
              candidate,
              result
      );

      throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE,
              ERROR_REDIS_SCRIPT_UNKNOWN_RESULT
      );
    }

    throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            ERROR_INVITE_CODE_EXHAUSTED
    );
  }

  @Override
  public LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId) {
    try {
      String result = lobbyLuaScriptExecutor.executeLeaveLobby(code, userId);

      LeaveLobbyResult leaveResult =
              lobbyLuaResultMapper.toLeaveLobbyResult(result, code, userId);

      lobbyRedisCommandRepository.cleanupReadyStatusAfterLeave(
              code,
              userId,
              leaveResult
      );

      return leaveResult;

    } catch (Exception e) {
      return new LeaveLobbyResult.Error("Lua 스크립트 실행 중 예외 발생: " + e.getMessage());
    }
  }

  @Override
  public KickLobbyResult executeKickLobbyProcess(
          String code,
          String requesterIdentifier,
          String targetUserIdentifier
  ) {
    try {
      String result = lobbyLuaScriptExecutor.executeKickLobby(
              code,
              requesterIdentifier,
              targetUserIdentifier
      );

      KickLobbyResult kickResult = lobbyLuaResultMapper.toKickLobbyResult(
              result,
              code,
              requesterIdentifier,
              targetUserIdentifier
      );

      lobbyRedisCommandRepository.cleanupReadyStatusAfterKick(
              code,
              targetUserIdentifier,
              kickResult
      );

      return kickResult;

    } catch (Exception e) {
      log.error(
              "강퇴 Lua 스크립트 실행 중 예외 발생 - lobbyCode: {}, requester: {}, target: {}",
              code,
              requesterIdentifier,
              targetUserIdentifier,
              e
      );

      return new KickLobbyResult.Error("Lua 스크립트 실행 중 예외 발생: " + e.getMessage());
    }
  }

  @Override
  public StartLobbyResult executeStartLobbyProcess(
          String code,
          String requesterIdentifier
  ) {
    try {
      lobbyRedisCommandRepository.cleanupStaleReadyParticipantsBeforeStart(
              code,
              requesterIdentifier
      );

      String result = lobbyLuaScriptExecutor.executeStartLobby(
              code,
              requesterIdentifier
      );

      return lobbyLuaResultMapper.toStartLobbyResult(
              result,
              code,
              requesterIdentifier
      );

    } catch (Exception e) {
      log.error(
              "게임 시작 Lua 스크립트 실행 중 예외 발생 - lobbyCode: {}, requester: {}",
              code,
              requesterIdentifier,
              e
      );

      return new StartLobbyResult.Error("Lua 스크립트 실행 중 예외 발생: " + e.getMessage());
    }
  }

  // =========================================================
  // Start Reconciliation
  // =========================================================

  @Override
  public void enqueueStartReconciliation(String code, String reason) {
    lobbyStartReconciliationRepository.enqueueStartReconciliation(code, reason);
  }

  @Override
  public String pollStartReconciliation() {
    return lobbyStartReconciliationRepository.pollStartReconciliation();
  }

  @Override
  public void requeueStartReconciliation(String payload) {
    lobbyStartReconciliationRepository.requeueStartReconciliation(payload);
  }

  @Override
  public void safeRequeueStartReconciliation(String payload) {
    lobbyStartReconciliationRepository.safeRequeueStartReconciliation(payload);
  }

  @Override
  public void incrementStartReconciliationMetric(String metricKey) {
    lobbyStartReconciliationRepository.incrementStartReconciliationMetric(metricKey);
  }

  @Override
  public List<String> getPublicLobbyCodesForCleanup(int limit) {
    return lobbyRedisQueryRepository.getPublicLobbyCodesForCleanup(limit);
  }
}