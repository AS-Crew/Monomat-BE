package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
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
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 로비 Redis Repository 구현체.
 *
 * [현재 책임]
 * 이 클래스는 기존 LobbyRepository 인터페이스 계약을 유지하는 facade 역할을 합니다.
 * 세부 Redis 책임은 역할별 컴포넌트로 점진적으로 분리합니다.
 *
 * [분리 완료]
 * - 초대 코드 생성: LobbyInviteCodeGenerator
 * - Lua 결과 매핑: LobbyLuaResultMapper
 * - Lua 실행: LobbyLuaScriptExecutor
 * - Redis 조회: LobbyRedisQueryRepository
 * - Redis command: LobbyRedisCommandRepository
 *
 * [아직 남은 책임]
 * - start reconciliation queue 처리
 *
 * [중요]
 * 이번 리팩토링은 외부 서비스 계층의 의존성을 바꾸지 않기 위한 내부 구조 개선입니다.
 * 따라서 LobbyRepository 인터페이스, Redis key 구조, Lua script 계약은 변경하지 않습니다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;
  private final LobbyInviteCodeGenerator lobbyInviteCodeGenerator;
  private final LobbyLuaResultMapper lobbyLuaResultMapper;
  private final LobbyLuaScriptExecutor lobbyLuaScriptExecutor;
  private final LobbyRedisQueryRepository lobbyRedisQueryRepository;
  private final LobbyRedisCommandRepository lobbyRedisCommandRepository;

  // =========================================================
  // create_lobby.lua 반환값 상수
  // =========================================================

  private static final String RESULT_OK = "OK";
  private static final String RESULT_LOCK_FAILED = "LOCK_FAILED";

  // =========================================================
  // 게임 시작 상태 재처리 상수
  // =========================================================

  private static final String RECONCILIATION_PAYLOAD_DELIMITER = "|";

  /**
   * 운영 확인이 필요한 Redis 정리 실패 로그 식별자.
   */
  private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

  /** 초대 코드 생성 실패 시 반환할 에러 메시지 */
  private static final String ERROR_INVITE_CODE_EXHAUSTED =
          "초대 코드 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

  /** Lua 스크립트 null 반환 시 에러 메시지 */
  private static final String ERROR_REDIS_SCRIPT_NULL =
          "Redis 스크립트 실행 결과가 null입니다. Redis 연결 상태를 확인해주세요.";

  /** Lua 스크립트가 예상하지 못한 값을 반환했을 때의 에러 메시지 */
  private static final String ERROR_REDIS_SCRIPT_UNKNOWN_RESULT =
          "Redis 로비 생성 처리 결과가 유효하지 않습니다.";

  // =========================================================
  // Redis 조회 위임
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
  public Optional<JoinLobbyResponse> findByInviteCode(String inviteCode) {
    return lobbyRedisQueryRepository.findByInviteCode(inviteCode);
  }

  @Override
  public int getCurrentPlayerCount(String inviteCode) {
    return lobbyRedisQueryRepository.getCurrentPlayerCount(inviteCode);
  }

  // =========================================================
  // Redis command 위임
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

  // =========================================================
  // Lua 기반 로비 상태 변경
  // =========================================================

  /**
   * Lua 스크립트로 SETNX 선점과 로비 데이터 저장을 원자적으로 수행하고 초대 코드를 반환합니다.
   */
  @Override
  public String saveToRedis(
          CreateLobbyRequest request,
          String userIdentifier,
          LobbyMapMetadata mapMetadata
  ) {
    for (int attempt = 0; attempt < LobbyDefaults.INVITE_CODE_MAX_RETRY; attempt++) {
      String candidate = lobbyInviteCodeGenerator.generate();

      String result = lobbyLuaScriptExecutor.executeCreateLobby(
              candidate,
              request,
              userIdentifier,
              mapMetadata
      );

      if (result == null) {
        log.error("Lua 스크립트 null 반환 - Redis 연결 오류 가능성. 코드: {}", candidate);
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ERROR_REDIS_SCRIPT_NULL
        );
      }

      if (RESULT_OK.equals(result)) {
        log.info(
                "로비 Redis 저장 완료 - 코드: {}, 방장: {}, mapId: {}",
                candidate,
                userIdentifier,
                mapMetadata != null ? mapMetadata.mapId() : null
        );
        return candidate;
      }

      if (RESULT_LOCK_FAILED.equals(result)) {
        log.warn(
                "초대 코드 충돌 - 재시도 {}/{}: {}",
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
      LeaveLobbyResult leaveResult = lobbyLuaResultMapper.toLeaveLobbyResult(result, code, userId);

      lobbyRedisCommandRepository.cleanupReadyStatusAfterLeave(code, userId, leaveResult);

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

      String result = lobbyLuaScriptExecutor.executeStartLobby(code, requesterIdentifier);

      return lobbyLuaResultMapper.toStartLobbyResult(result, code, requesterIdentifier);

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
  // Start reconciliation queue
  // =========================================================

  @Override
  public void enqueueStartReconciliation(String code, String reason) {
    String payload = String.join(
            RECONCILIATION_PAYLOAD_DELIMITER,
            code,
            reason,
            "0",
            String.valueOf(System.currentTimeMillis())
    );

    try {
      redisTemplate.opsForList().rightPush(
              RedisKeys.LOBBY_START_RECONCILIATION_QUEUE,
              payload
      );
      incrementStartReconciliationMetric(RedisKeys.METRIC_LOBBY_START_RECONCILIATION_ENQUEUED);

      log.error(
              "{} 게임 시작 상태 재처리 큐 적재 완료 - code: {}, reason: {}, queueKey: {}",
              LOG_MONITORING_REQUIRED,
              code,
              reason,
              RedisKeys.LOBBY_START_RECONCILIATION_QUEUE
      );
    } catch (Exception e) {
      incrementStartReconciliationMetric(RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED);

      log.error(
              "{} 게임 시작 상태 재처리 큐 적재 실패 - code: {}, reason: {}, queueKey: {}. "
                      + "Redis-DB 불일치 수동 확인이 필요합니다.",
              LOG_MONITORING_REQUIRED,
              code,
              reason,
              RedisKeys.LOBBY_START_RECONCILIATION_QUEUE,
              e
      );
    }
  }

  @Override
  public String pollStartReconciliation() {
    return redisTemplate.opsForList().leftPop(RedisKeys.LOBBY_START_RECONCILIATION_QUEUE);
  }

  @Override
  public void requeueStartReconciliation(String payload) {
    redisTemplate.opsForList().rightPush(
            RedisKeys.LOBBY_START_RECONCILIATION_QUEUE,
            payload
    );
  }

  @Override
  public void incrementStartReconciliationMetric(String metricKey) {
    try {
      redisTemplate.opsForValue().increment(metricKey);
    } catch (Exception e) {
      log.warn("게임 시작 상태 재처리 metric 증가 실패 - metricKey: {}", metricKey, e);
    }
  }
}