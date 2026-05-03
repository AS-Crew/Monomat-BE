package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<String> leaveLobbyScript;

  // =========================================================
  // Lua 스크립트 반환값 상수
  // =========================================================

  private static final String RESULT_DESTROYED = "DESTROYED";
  private static final String RESULT_LEFT = "LEFT";
  private static final String RESULT_DELEGATED_PREFIX = "DELEGATED:";

  // =========================================================
  // 초대 코드 생성 상수
  // =========================================================

  /** 초대 코드 생성용 보안 난수 생성기 */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /** 초대 코드 생성 실패 시 반환할 에러 메시지 */
  private static final String ERROR_INVITE_CODE_EXHAUSTED =
          "초대 코드 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

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

  /**
   * Redis에 로비 데이터를 저장하고 초대 코드를 반환한다.
   *
   * [저장 구조]
   * - lobby:{code}              Hash — 로비 메타 정보
   * - lobby:{code}:participants Set  — 참여자 목록 (방장 선 추가)
   * - lobby:{code}:order        List — 입장 순서 (방장 선 추가)
   * - lobby:public              Set  — 공개 로비 목록 (isPrivate=false 시만)
   *
   * [SETNX 기반 코드 중복 방지]
   * lobby:code:lock:{code} 키를 SETNX로 원자적 선점한다.
   * 선점 실패 시 새 코드를 생성하여 재시도
   * LobbyDefaults.INVITE_CODE_MAX_RETRY 초과 시 503 반환한다.
   */
  @Override
  public String saveToRedis(CreateLobbyRequest request, String userIdentifier) {
    String inviteCode = acquireInviteCode(userIdentifier);

    storelobbyData(request, inviteCode, userIdentifier);

    log.info("로비 Redis 저장 완료 - 코드: {}, 방장: {}", inviteCode, userIdentifier);
    return inviteCode;
  }

  @Override
  public LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId) {
    List<String> keys = List.of(
            RedisKeys.lobbyKey(code),
            RedisKeys.lobbyParticipantsKey(code),
            RedisKeys.lobbyOrderKey(code),
            RedisKeys.LOBBY_PUBLIC
    );

    try {
      String result = redisTemplate.execute(leaveLobbyScript, keys, userId, code);
      return parseLuaResult(result, code, userId);
    } catch (Exception e) {
      return new LeaveLobbyResult.Error(
              "Lua 스크립트 실행 중 예외 발생: " + e.getMessage());
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
              .maxPlayers(parseNullableInt(data.get(RedisKeys.FIELD_MAX_PLAYERS)))
              .isPrivate(Boolean.parseBoolean(
                      (String) data.get(RedisKeys.FIELD_IS_PRIVATE)))
              .build());
    }

    return result;
  }

  // =========================================================
  // private 메서드
  // =========================================================

  /**
   * SETNX로 초대 코드를 원자적으로 선점한다.
   * 최대 재시도 횟수 초과 시 503 예외를 던진다.
   */
  private String acquireInviteCode(String userIdentifier) {
    for (int attempt = 0; attempt < LobbyDefaults.INVITE_CODE_MAX_RETRY; attempt++) {
      String candidate = generateInviteCode();
      String lockKey = RedisKeys.lobbyCodeLockKey(candidate);

      Boolean acquired = redisTemplate.opsForValue()
              .setIfAbsent(lockKey, userIdentifier, LobbyDefaults.INVITE_CODE_LOCK_TTL);

      if (Boolean.TRUE.equals(acquired)) {
        return candidate;
      }

      log.warn("초대 코드 충돌 - 재시도 {}/{}: {}",
              attempt + 1, LobbyDefaults.INVITE_CODE_MAX_RETRY, candidate);
    }

    throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, ERROR_INVITE_CODE_EXHAUSTED);
  }

  /**
   * Redis에 로비 관련 데이터를 저장
   * Hash, 참여자 Set, 입장 순서 List, 공개 Set에 각각 기록한다.
   */
  private void storelobbyData(
          CreateLobbyRequest request,
          String inviteCode,
          String userIdentifier
  ) {
    redisTemplate.opsForHash().putAll(
            RedisKeys.lobbyKey(inviteCode),
            buildLobbyHashData(request, inviteCode, userIdentifier)
    );

    redisTemplate.opsForSet().add(
            RedisKeys.lobbyParticipantsKey(inviteCode), userIdentifier);

    redisTemplate.opsForList().rightPush(
            RedisKeys.lobbyOrderKey(inviteCode), userIdentifier);

    if (!request.isPrivate()) {
      redisTemplate.opsForSet().add(RedisKeys.LOBBY_PUBLIC, inviteCode);
    }
  }

  /**
   * RedisKeys.FIELD_* 상수 기반으로 Redis Hash에 저장할 로비 데이터를 구성한다.
   */
  private Map<String, String> buildLobbyHashData(
          CreateLobbyRequest request,
          String inviteCode,
          String userIdentifier
  ) {
    Map<String, String> data = new HashMap<>();
    data.put(RedisKeys.FIELD_CODE, inviteCode);
    data.put(RedisKeys.FIELD_HOST_USER_ID, userIdentifier);
    data.put(RedisKeys.FIELD_TITLE, request.title());
    data.put(RedisKeys.FIELD_MAX_PLAYERS, String.valueOf(request.maxPlayers()));
    data.put(RedisKeys.FIELD_IS_PRIVATE, String.valueOf(request.isPrivate()));
    data.put(RedisKeys.FIELD_STATUS, LobbyStatus.WAITING.name());
    return data;
  }

  /**
   * LobbyDefaults 상수 기반으로 6자리 초대 코드를 생성한다.
   *
   * [SecureRandom 사용 이유]
   * Random 대신 SecureRandom을 사용하여 코드 예측 가능성을 낮춘다.
   * 게임 특성상 코드 추측으로 비공개 로비에 무단 입장하는 것을 방지한다..
   */
  private String generateInviteCode() {
    StringBuilder sb = new StringBuilder(LobbyDefaults.INVITE_CODE_LENGTH);
    for (int i = 0; i < LobbyDefaults.INVITE_CODE_LENGTH; i++) {
      sb.append(LobbyDefaults.INVITE_CODE_CHARACTERS.charAt(
              SECURE_RANDOM.nextInt(LobbyDefaults.INVITE_CODE_CHARACTERS.length())
      ));
    }
    return sb.toString();
  }

  private LeaveLobbyResult parseLuaResult(String result, String code, String userId) {
    if (result == null) {
      return new LeaveLobbyResult.Error("Lua 스크립트 반환값이 null입니다.");
    }
    if (RESULT_DESTROYED.equals(result)) {
      return new LeaveLobbyResult.Destroyed(code);
    }
    if (RESULT_LEFT.equals(result)) {
      return new LeaveLobbyResult.Left(code, userId);
    }
    if (result.startsWith(RESULT_DELEGATED_PREFIX)) {
      String newHostId = result.substring(RESULT_DELEGATED_PREFIX.length());
      return new LeaveLobbyResult.Delegated(code, newHostId);
    }
    return new LeaveLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
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
}