package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyInviteCodeGenerator;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyLuaResultMapper;
import io.github.ascrew.monomatbe.domain.lobby.repository.redis.LobbyLuaScriptExecutor;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;
  private final LobbyInviteCodeGenerator lobbyInviteCodeGenerator;
  private final LobbyLuaResultMapper lobbyLuaResultMapper;
  private final LobbyLuaScriptExecutor lobbyLuaScriptExecutor;

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
   * 운영 확인이 필요한 Redis 정리 실패 로그 식별자
   *
   * 현재 프로젝트에 별도 알림/재처리 큐 인프라가 없으므로,
   * 운영 로그 수집 시스템에서 이 키워드를 기준으로 알림을 연계할 수 있도록 한다.
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

  /**
   * 로비가 존재하지 않거나 TTL이 만료된 경우 반환할 빈 Optional.
   * 매번 새 객체를 생성하지 않기 위해 상수로 관리한다.
   */
  private static final Optional<JoinLobbyResponse> EMPTY_LOBBY = Optional.empty();

  private static final String ERROR_INVALID_LOBBY_DATA =
          "로비 정보가 유효하지 않습니다.";

  // =========================================================
  // 공개 메서드
  // =========================================================

  @Override
  public boolean existsByCode(String code) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.lobbyKey(code)));
  }

  @Override
  public boolean isParticipant(String code, String userId) {
    return Boolean.TRUE.equals(
            redisTemplate.opsForSet().isMember(RedisKeys.lobbyParticipantsKey(code), userId)
    );
  }

  @Override
  public void updateReadyStatus(String code, String userIdentifier, boolean ready) {
    String readyKey = RedisKeys.lobbyReadyKey(code);

    if (ready) {
      redisTemplate.opsForSet().add(readyKey, userIdentifier);
      return;
    }

    redisTemplate.opsForSet().remove(readyKey, userIdentifier);
  }

  /**
   * 로비 참여자 목록을 입장 순서 기준으로 조회한다.
   */
  @Override
  public List<String> getParticipantIdentifiers(String code) {
    Set<String> participantSet = redisTemplate.opsForSet()
            .members(RedisKeys.lobbyParticipantsKey(code));

    if (participantSet == null || participantSet.isEmpty()) {
      return new ArrayList<>();
    }

    List<String> orderedParticipants = redisTemplate.opsForList()
            .range(RedisKeys.lobbyOrderKey(code), 0, -1);

    if (orderedParticipants == null || orderedParticipants.isEmpty()) {
      return new ArrayList<>(participantSet);
    }

    List<String> result = new ArrayList<>();

    for (String userIdentifier : orderedParticipants) {
      if (participantSet.contains(userIdentifier)) {
        result.add(userIdentifier);
      }
    }

    for (String userIdentifier : participantSet) {
      if (!result.contains(userIdentifier)) {
        result.add(userIdentifier);
      }
    }

    return result;
  }

  /**
   * ready 상태인 참여자 식별자 목록을 조회한다.
   */
  @Override
  public Set<String> getReadyParticipantIdentifiers(String code) {
    Set<String> readyParticipants = redisTemplate.opsForSet()
            .members(RedisKeys.lobbyReadyKey(code));

    if (readyParticipants == null || readyParticipants.isEmpty()) {
      return Set.of();
    }

    return readyParticipants;
  }

  /**
   * Lua 스크립트로 SETNX 선점과 로비 데이터 저장을 원자적으로 수행하고 초대 코드를 반환한다.
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
                HttpStatus.SERVICE_UNAVAILABLE, ERROR_REDIS_SCRIPT_NULL);
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
            HttpStatus.SERVICE_UNAVAILABLE, ERROR_INVITE_CODE_EXHAUSTED);
  }

  /**
   * DB Insert 실패 시 Redis에 저장된 로비 데이터를 보상 삭제한다.
   */
  @Override
  public boolean deleteFromRedis(String inviteCode) {
    List<String> keysToDelete = List.of(
            RedisKeys.lobbyKey(inviteCode),
            RedisKeys.lobbyParticipantsKey(inviteCode),
            RedisKeys.lobbyOrderKey(inviteCode),
            RedisKeys.lobbyKickedKey(inviteCode),
            RedisKeys.lobbyReadyKey(inviteCode),
            RedisKeys.lobbyCodeLockKey(inviteCode)
    );

    try {
      redisTemplate.delete(keysToDelete);
      redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, inviteCode);

      log.info("Redis 보상 삭제 완료 - code: {}, keys: {}", inviteCode, keysToDelete);
      return true;

    } catch (Exception e) {
      log.error(
              "{} Redis 보상 삭제 실패 - code: {}, keys: {}, publicLobbyKey: {}. "
                      + "로비 잔여 데이터가 조회/ready/canStart 계산에 영향을 줄 수 있으므로 수동 정리 또는 재처리가 필요합니다.",
              LOG_MONITORING_REQUIRED,
              inviteCode,
              keysToDelete,
              RedisKeys.LOBBY_PUBLIC,
              e
      );
      return false;
    }
  }

  @Override
  public LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId) {
    try {
      String result = lobbyLuaScriptExecutor.executeLeaveLobby(code, userId);
      LeaveLobbyResult leaveResult = lobbyLuaResultMapper.toLeaveLobbyResult(result, code, userId);

      cleanupReadyStatusAfterLeave(code, userId, leaveResult);

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

      cleanupReadyStatusAfterKick(code, targetUserIdentifier, kickResult);

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
      cleanupStaleReadyParticipantsBeforeStart(code, requesterIdentifier);

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

  /**
   * Redis 로비 상태를 WAITING으로 보상 롤백한다.
   */
  @Override
  public boolean rollbackStartedLobbyStatus(String code) {
    String lobbyKey = RedisKeys.lobbyKey(code);

    try {
      Map<Object, Object> lobbyData = redisTemplate.opsForHash().entries(lobbyKey);

      if (lobbyData.isEmpty()) {
        log.error(
                "{} 게임 시작 Redis 보상 롤백 실패 - 로비 데이터 없음. code: {}, lobbyKey: {}",
                LOG_MONITORING_REQUIRED,
                code,
                lobbyKey
        );
        return false;
      }

      redisTemplate.opsForHash().put(
              lobbyKey,
              RedisKeys.FIELD_STATUS,
              LobbyStatus.WAITING.name()
      );

      String rawIsPrivate = (String) lobbyData.get(RedisKeys.FIELD_IS_PRIVATE);
      boolean restoredPublic = restorePublicLobbyIfClearlyPublic(
              code,
              lobbyKey,
              rawIsPrivate
      );

      log.warn(
              "게임 시작 Redis 보상 롤백 완료 - code: {}, status: {}, restoredPublic: {}, rawIsPrivate: {}",
              code,
              LobbyStatus.WAITING.name(),
              restoredPublic,
              rawIsPrivate
      );

      return true;

    } catch (Exception e) {
      log.error(
              "{} 게임 시작 Redis 보상 롤백 실패 - code: {}, lobbyKey: {}. "
                      + "Redis는 PLAYING인데 DB는 WAITING일 수 있으므로 재처리 큐 확인이 필요합니다.",
              LOG_MONITORING_REQUIRED,
              code,
              lobbyKey,
              e
      );
      return false;
    }
  }

  private boolean restorePublicLobbyIfClearlyPublic(
          String code,
          String lobbyKey,
          String rawIsPrivate
  ) {
    if (rawIsPrivate == null || rawIsPrivate.isBlank()) {
      log.error(
              "{} 게임 시작 Redis 보상 롤백 중 is_private 필드 누락 - public 복구 생략. "
                      + "code: {}, lobbyKey: {}",
              LOG_MONITORING_REQUIRED,
              code,
              lobbyKey
      );
      return false;
    }

    if ("false".equals(rawIsPrivate)) {
      redisTemplate.opsForSet().add(RedisKeys.LOBBY_PUBLIC, code);
      return true;
    }

    if (!"true".equals(rawIsPrivate)) {
      log.error(
              "{} 게임 시작 Redis 보상 롤백 중 알 수 없는 is_private 값 - public 복구 생략. "
                      + "code: {}, lobbyKey: {}, rawIsPrivate: {}",
              LOG_MONITORING_REQUIRED,
              code,
              lobbyKey,
              rawIsPrivate
      );
    }

    return false;
  }

  /**
   * Redis-DB 상태 불일치 재처리 요청을 Redis 큐에 적재한다.
   */
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

  @Override
  public List<LobbyRedisDto> getPublicLobbies() {
    Set<String> publicLobbyCodes =
            redisTemplate.opsForSet().members(RedisKeys.LOBBY_PUBLIC);

    if (publicLobbyCodes == null || publicLobbyCodes.isEmpty()) {
      return new ArrayList<>();
    }

    List<LobbyRedisDto> result = new ArrayList<>();

    for (String code : publicLobbyCodes) {
      Map<Object, Object> data =
              redisTemplate.opsForHash().entries(RedisKeys.lobbyKey(code));

      if (data.isEmpty()) continue;

      result.add(LobbyRedisDto.builder()
              .code((String) data.get(RedisKeys.FIELD_CODE))
              .hostId((String) data.get(RedisKeys.FIELD_HOST_USER_ID))
              .title((String) data.get(RedisKeys.FIELD_TITLE))
              .status((String) data.get(RedisKeys.FIELD_STATUS))
              .mapId(parseNullableLong(data.get(RedisKeys.FIELD_MAP_ID)))
              .mapTitle((String) data.get(RedisKeys.FIELD_MAP_TITLE))
              .mapCategory(toDisplayMapCategory((String) data.get(RedisKeys.FIELD_MAP_CATEGORY)))
              .maxPlayers(parseNullableInt(data.get(RedisKeys.FIELD_MAX_PLAYERS)))
              .currentPlayers(getCurrentPlayerCount(code))
              .isPrivate(Boolean.parseBoolean((String) data.get(RedisKeys.FIELD_IS_PRIVATE)))
              .build());
    }

    return result;
  }

  @Override
  public Optional<JoinLobbyResponse> findByInviteCode(String inviteCode) {
    Map<Object, Object> data =
            redisTemplate.opsForHash().entries(RedisKeys.lobbyKey(inviteCode));

    if (data.isEmpty()) {
      return EMPTY_LOBBY;
    }

    int currentPlayers = getCurrentPlayerCount(inviteCode);

    int maxPlayers = parseRequiredPositiveInt(
            data.get(RedisKeys.FIELD_MAX_PLAYERS),
            RedisKeys.FIELD_MAX_PLAYERS,
            inviteCode
    );

    return Optional.of(JoinLobbyResponse.builder()
            .inviteCode(inviteCode)
            .title((String) data.get(RedisKeys.FIELD_TITLE))
            .hostId((String) data.get(RedisKeys.FIELD_HOST_USER_ID))
            .maxPlayers(maxPlayers)
            .currentPlayers(currentPlayers)
            .status((String) data.get(RedisKeys.FIELD_STATUS))
            .mapId(parseNullableLong(data.get(RedisKeys.FIELD_MAP_ID)))
            .mapTitle((String) data.get(RedisKeys.FIELD_MAP_TITLE))
            .mapCategory(toDisplayMapCategory((String) data.get(RedisKeys.FIELD_MAP_CATEGORY)))
            .build());
  }

  @Override
  public int getCurrentPlayerCount(String inviteCode) {
    Long count = redisTemplate.opsForSet()
            .size(RedisKeys.lobbyParticipantsKey(inviteCode));

    return count != null ? count.intValue() : 0;
  }

  private void cleanupReadyStatusAfterLeave(
          String code,
          String userId,
          LeaveLobbyResult leaveResult
  ) {
    String readyKey = RedisKeys.lobbyReadyKey(code);

    try {
      if (leaveResult instanceof LeaveLobbyResult.Destroyed) {
        redisTemplate.delete(readyKey);
        return;
      }

      if (leaveResult instanceof LeaveLobbyResult.Left
              || leaveResult instanceof LeaveLobbyResult.Delegated) {
        redisTemplate.opsForSet().remove(readyKey, userId);
      }
    } catch (Exception e) {
      log.error(
              "{} 퇴장 후 ready 상태 정리 실패 - lobbyCode: {}, userId: {}, readyKey: {}, leaveResult: {}. "
                      + "ready Set 잔여 데이터가 canStart 계산을 왜곡할 수 있으므로 수동 정리 또는 재처리가 필요합니다.",
              LOG_MONITORING_REQUIRED,
              code,
              userId,
              readyKey,
              leaveResult.getClass().getSimpleName(),
              e
      );
    }
  }

  private void cleanupReadyStatusAfterKick(
          String code,
          String targetUserIdentifier,
          KickLobbyResult kickResult
  ) {
    if (!(kickResult instanceof KickLobbyResult.Kicked)) {
      return;
    }

    try {
      redisTemplate.opsForSet().remove(
              RedisKeys.lobbyReadyKey(code),
              targetUserIdentifier
      );
    } catch (Exception e) {
      log.warn(
              "강퇴 후 ready 상태 정리 실패 - lobbyCode: {}, targetUserIdentifier: {}",
              code,
              targetUserIdentifier,
              e
      );
    }
  }

  private void cleanupStaleReadyParticipantsBeforeStart(
          String code,
          String requesterIdentifier
  ) {
    String participantsKey = RedisKeys.lobbyParticipantsKey(code);
    String readyKey = RedisKeys.lobbyReadyKey(code);

    try {
      Set<String> participants = redisTemplate.opsForSet().members(participantsKey);
      Set<String> readyParticipants = redisTemplate.opsForSet().members(readyKey);

      if (readyParticipants == null || readyParticipants.isEmpty()) {
        return;
      }

      Set<String> participantSet = participants != null ? participants : Set.of();

      Set<String> staleReadyParticipants = new HashSet<>(readyParticipants);
      staleReadyParticipants.removeAll(participantSet);

      if (staleReadyParticipants.isEmpty()) {
        return;
      }

      redisTemplate.opsForSet().remove(
              readyKey,
              staleReadyParticipants.toArray()
      );

      incrementStartReconciliationMetric(RedisKeys.METRIC_LOBBY_READY_STALE_CLEANUP);

      log.warn(
              "{} 게임 시작 전 stale ready 데이터 정리 - lobbyCode: {}, requester: {}, "
                      + "participantsKey: {}, readyKey: {}, staleReadyParticipants: {}",
              LOG_MONITORING_REQUIRED,
              code,
              requesterIdentifier,
              participantsKey,
              readyKey,
              staleReadyParticipants
      );

    } catch (Exception e) {
      log.error(
              "{} 게임 시작 전 ready 정합성 스캔 실패 - lobbyCode: {}, requester: {}, "
                      + "participantsKey: {}, readyKey: {}",
              LOG_MONITORING_REQUIRED,
              code,
              requesterIdentifier,
              participantsKey,
              readyKey,
              e
      );
    }
  }

  private Long parseNullableLong(Object value) {
    if (value == null) return null;
    try {
      return Long.parseLong((String) value);
    } catch (NumberFormatException e) {
      log.warn("Redis Hash 필드 Long 파싱 실패 - 값: {}", value);
      return null;
    }
  }

  private Integer parseNullableInt(Object value) {
    if (value == null) return null;
    try {
      return Integer.parseInt((String) value);
    } catch (NumberFormatException e) {
      log.warn("Redis Hash 필드 Integer 파싱 실패 - 값: {}", value);
      return null;
    }
  }

  private String toDisplayMapCategory(String rawCategory) {
    try {
      return MapCategory.toDisplayValue(rawCategory);
    } catch (IllegalArgumentException e) {
      log.warn("알 수 없는 맵 카테고리 값 - rawCategory: {}", rawCategory);
      return rawCategory;
    }
  }

  private int parseRequiredPositiveInt(Object value, String fieldName, String inviteCode) {
    if (value == null) {
      log.error("Redis 로비 필수 필드 누락 - inviteCode: {}, field: {}",
              inviteCode, fieldName);
      throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              ERROR_INVALID_LOBBY_DATA
      );
    }

    try {
      int parsed = Integer.parseInt((String) value);

      if (parsed <= 0) {
        log.error("Redis 로비 필수 필드 값이 유효하지 않음 - inviteCode: {}, field: {}, value: {}",
                inviteCode, fieldName, value);
        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ERROR_INVALID_LOBBY_DATA
        );
      }

      return parsed;

    } catch (NumberFormatException e) {
      log.error("Redis 로비 필수 필드 숫자 파싱 실패 - inviteCode: {}, field: {}, value: {}",
              inviteCode, fieldName, value, e);
      throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR,
              ERROR_INVALID_LOBBY_DATA
      );
    }
  }
}