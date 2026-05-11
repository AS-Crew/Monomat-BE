package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.JoinLobbyResponse;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  /**
   * Lua 스크립트에 선택 값이 없음을 표현하기 위한 값.
   * Redis Hash에 "null" 문자열이 저장되는 것을 방지하기 위해 빈 문자열을 사용합니다.
   */
  private static final String EMPTY_REDIS_VALUE = "";

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<String> leaveLobbyScript;
  private final RedisScript<String> createLobbyScript;
  private final RedisScript<String> kickLobbyScript;

  // =========================================================
  // create_lobby.lua 반환값 상수
  // =========================================================

  private static final String RESULT_OK = "OK";
  private static final String RESULT_LOCK_FAILED = "LOCK_FAILED";

  // =========================================================
  // leave_lobby.lua 반환값 상수
  // =========================================================

  private static final String RESULT_DESTROYED = "DESTROYED";
  private static final String RESULT_LEFT = "LEFT";
  private static final String RESULT_DELEGATED_PREFIX = "DELEGATED:";

  // =========================================================
  // kick_lobby.lua 반환값 상수
  // =========================================================

  private static final String RESULT_KICKED_PREFIX = "KICKED:";
  private static final String RESULT_LOBBY_NOT_FOUND = "LOBBY_NOT_FOUND";
  private static final String RESULT_HOST_NOT_FOUND = "HOST_NOT_FOUND";
  private static final String RESULT_FORBIDDEN = "FORBIDDEN";
  private static final String RESULT_CANNOT_KICK_SELF = "CANNOT_KICK_SELF";
  private static final String RESULT_TARGET_NOT_PARTICIPANT = "TARGET_NOT_PARTICIPANT";

  // =========================================================
  // isPrivate 정규화 상수
  // =========================================================

  /** Lua 스크립트에 전달할 공개 로비 isPrivate 값 */
  private static final String IS_PRIVATE_TRUE = "true";

  /** Lua 스크립트에 전달할 비공개 로비 isPrivate 값 */
  private static final String IS_PRIVATE_FALSE = "false";

  // =========================================================
  // 초대 코드 생성 상수
  // =========================================================

  /** 초대 코드 생성용 보안 난수 생성기 */
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /** 초대 코드 생성 실패 시 반환할 에러 메시지 */
  private static final String ERROR_INVITE_CODE_EXHAUSTED =
          "초대 코드 생성에 실패했습니다. 잠시 후 다시 시도해주세요.";

  /** Lua 스크립트 null 반환 시 에러 메시지 */
  private static final String ERROR_REDIS_SCRIPT_NULL =
          "Redis 스크립트 실행 결과가 null입니다. Redis 연결 상태를 확인해주세요.";

  // =========================================================
  // findByInviteCode 관련 상수
  // =========================================================

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
   * Lua 스크립트로 SETNX 선점과 로비 데이터 저장을 원자적으로 수행하고 초대 코드를 반환한다.
   *
   * [저장 구조]
   * - lobby:{code}              Hash — 로비 메타 정보
   * - lobby:{code}:participants Set  — 참여자 목록 (방장 선 추가)
   * - lobby:{code}:order        List — 입장 순서 (방장 선 추가)
   * - lobby:public              Set  — 공개 로비 목록 (isPrivate=false 시만)
   *
   * [재시도 전략]
   * - LOCK_FAILED : 코드 충돌 → 새 코드 생성 후 재시도
   * - null        : Redis 오류 → 재시도하지 않고 즉시 503 반환
   * - 최대 재시도 횟수 초과 시 503 반환
   */
  @Override
  public String saveToRedis(
          CreateLobbyRequest request,
          String userIdentifier,
          LobbyMapMetadata mapMetadata
  ) {
    for (int attempt = 0; attempt < LobbyDefaults.INVITE_CODE_MAX_RETRY; attempt++) {
      String candidate = generateInviteCode();
      String result = executeCreateLobbyScript(candidate, request, userIdentifier, mapMetadata);

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

      log.warn("초대 코드 충돌 - 재시도 {}/{}: {}",
              attempt + 1, LobbyDefaults.INVITE_CODE_MAX_RETRY, candidate);
    }

    throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, ERROR_INVITE_CODE_EXHAUSTED);
  }

  /**
   * DB Insert 실패 시 Redis에 저장된 로비 데이터를 보상 삭제한다.
   *
   * [삭제 실패 처리]
   * 보상 삭제 실패 시 ERROR 로그를 남기고 false를 반환한다.
   * 서비스 레이어에서 반환값을 확인하여 추가 알림 처리가 가능하다.
   *
   * @return 보상 삭제 성공 여부 (true: 성공, false: 실패)
   */
  @Override
  public boolean deleteFromRedis(String inviteCode) {
    try {
      redisTemplate.delete(List.of(
              RedisKeys.lobbyKey(inviteCode),
              RedisKeys.lobbyParticipantsKey(inviteCode),
              RedisKeys.lobbyOrderKey(inviteCode),
              RedisKeys.lobbyKickedKey(inviteCode),
              RedisKeys.lobbyReadyKey(inviteCode),
              RedisKeys.lobbyCodeLockKey(inviteCode)
      ));

      redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, inviteCode);

      log.info("Redis 보상 삭제 완료 - 코드: {}", inviteCode);
      return true;

    } catch (Exception e) {
      log.error("Redis 보상 삭제 실패 - 코드: {}. 수동 정리 필요.", inviteCode, e);
      return false;
    }
  }

  @Override
  public LeaveLobbyResult executeLeaveLobbyProcess(String code, String userId) {
    List<String> keys = List.of(
            RedisKeys.lobbyKey(code),
            RedisKeys.lobbyParticipantsKey(code),
            RedisKeys.lobbyOrderKey(code),
            RedisKeys.lobbyKickedKey(code),
            RedisKeys.LOBBY_PUBLIC
    );

    try {
      String result = redisTemplate.execute(leaveLobbyScript, keys, userId, code);
      LeaveLobbyResult leaveResult = parseLuaResult(result, code, userId);

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
    List<String> keys = List.of(
            RedisKeys.lobbyKey(code),
            RedisKeys.lobbyParticipantsKey(code),
            RedisKeys.lobbyOrderKey(code),
            RedisKeys.lobbyKickedKey(code),
            RedisKeys.lobbyUserSessionKey(code, targetUserIdentifier),
            RedisKeys.lobbyUserSessionSequenceKey(code, targetUserIdentifier)
    );

    try {
      String result = redisTemplate.execute(
              kickLobbyScript,
              keys,
              requesterIdentifier,
              targetUserIdentifier
      );

      KickLobbyResult kickResult = parseKickLuaResult(
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
              .mapCategory(toDisplayMapCategory((String) data.get(RedisKeys.FIELD_MAP_CATEGORY)))              .maxPlayers(parseNullableInt(data.get(RedisKeys.FIELD_MAX_PLAYERS)))
              .currentPlayers(getCurrentPlayerCount(code))
              .isPrivate(Boolean.parseBoolean((String) data.get(RedisKeys.FIELD_IS_PRIVATE)))
              .build());
    }

    return result;
  }

  /**
   * 초대 코드로 로비 입장에 필요한 정보를 조회한다.
   *
   * [조회 전략]
   * HGETALL로 lobby:{code} Hash를 한 번에 읽어 응답 객체를 구성한다.
   * currentPlayers는 participants Set의 SCARD로 별도 조회한다.
   *
   * [mapCategory]
   * 맵 선택 기능 구현 전까지 null로 반환한다.
   * 맵 선택 이슈에서 Redis Hash에 map_category 필드가 추가되면
   * FIELD_MAP_CATEGORY 상수로 조회한다.
   *
   * @param inviteCode 로비 초대 코드
   * @return 로비 정보 Optional (로비 미존재 시 empty)
   */
  @Override
  public Optional<JoinLobbyResponse> findByInviteCode(String inviteCode) {
    // HGETALL로 Hash 전체를 한 번에 읽어 네트워크 왕복 횟수를 최소화한다.
    Map<Object, Object> data =
            redisTemplate.opsForHash().entries(RedisKeys.lobbyKey(inviteCode));

    // 로비가 존재하지 않거나 TTL이 만료된 경우
    if (data.isEmpty()) {
      return EMPTY_LOBBY;
    }

    // 현재 참여 인원은 participants Set의 SCARD로 조회한다.
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
            .mapCategory(toDisplayMapCategory((String) data.get(RedisKeys.FIELD_MAP_CATEGORY)))            .build());
  }

  /**
   * 해당 로비의 현재 참여 인원 수를 반환한다.
   *
   * [구현 방식]
   * lobby:{code}:participants Set의 SCARD 명령으로 조회한다.
   * null 반환 시 Redis 연결 이상이므로 0으로 폴백하여 NPE를 방지한다.
   *
   * @param inviteCode 로비 초대 코드
   * @return 현재 참여 인원 수 (Redis 오류 시 0)
   */
  @Override
  public int getCurrentPlayerCount(String inviteCode) {
    Long count = redisTemplate.opsForSet()
            .size(RedisKeys.lobbyParticipantsKey(inviteCode));

    // null은 Redis 연결 이상을 의미한다.
    // 0으로 폴백하여 NPE를 방지하고, 서비스 레이어에서 정상 흐름을 유지한다.
    return count != null ? count.intValue() : 0;
  }

  // =========================================================
  // private 메서드
  // =========================================================

  /**
   * create_lobby.lua를 실행하여 SETNX + 로비 데이터 저장을 원자적으로 수행합니다.
   *
   * [맵 정보 저장 정책]
   * mapMetadata가 null이면 빈 문자열을 Lua에 전달합니다.
   * Lua는 mapId가 빈 문자열인 경우 map_id, map_title, map_category 필드를 저장하지 않습니다.
   *
   * @return "OK" | "LOCK_FAILED" | null
   */
  private String executeCreateLobbyScript(
          String inviteCode,
          CreateLobbyRequest request,
          String userIdentifier,
          LobbyMapMetadata mapMetadata
  ) {
    List<String> keys = List.of(
            RedisKeys.lobbyCodeLockKey(inviteCode),
            RedisKeys.lobbyKey(inviteCode),
            RedisKeys.LOBBY_PUBLIC
    );

    String lockTtlMs = String.valueOf(LobbyDefaults.INVITE_CODE_LOCK_TTL.toMillis());
    String isPrivateValue = normalizeIsPrivate(request.isPrivate());

    return redisTemplate.execute(
            createLobbyScript,
            keys,
            userIdentifier,
            lockTtlMs,
            inviteCode,
            request.title(),
            String.valueOf(request.maxPlayers()),
            isPrivateValue,
            LobbyStatus.WAITING.name(),

            // 맵 미선택 시 빈 문자열을 전달하여 Redis에 "null" 문자열이 저장되지 않게 합니다.
            mapMetadata != null ? String.valueOf(mapMetadata.mapId()) : EMPTY_REDIS_VALUE,
            mapMetadata != null ? mapMetadata.mapTitle() : EMPTY_REDIS_VALUE,
            mapMetadata != null ? mapMetadata.mapCategory() : EMPTY_REDIS_VALUE
    );
  }

  /**
   * isPrivate 값을 Lua 스크립트와 일치하는 소문자 문자열로 정규화한다.
   *
   * [정규화 이유]
   * Lua 스크립트(create_lobby.lua)에서 isPrivate 값을
   * if isPrivate == "false" then 으로 비교한다.
   * IS_PRIVATE_TRUE / IS_PRIVATE_FALSE 상수를 사용하여
   * 대소문자 불일치나 예상치 못한 값이 전달되는 것을 방지한다.
   *
   * @param isPrivate 로비 비공개 여부
   * @return "true" (비공개) 또는 "false" (공개)
   */
  private String normalizeIsPrivate(boolean isPrivate) {
    return isPrivate ? IS_PRIVATE_TRUE : IS_PRIVATE_FALSE;
  }

  /**
   * LobbyDefaults 상수 기반으로 6자리 초대 코드를 생성한다.
   *
   * [SecureRandom 사용 이유]
   * Random 대신 SecureRandom을 사용하여 코드 예측 가능성을 낮춘다.
   * 게임 특성상 코드 추측으로 비공개 로비에 무단 입장하는 것을 방지한다.
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

  /**
   * 퇴장 처리 결과에 따라 ready Set을 정리한다.
   *
   * [필요 이유]
   * 참여자가 로비를 나가면 더 이상 준비 상태에 포함되면 안 된다.
   * 로비가 폭파된 경우에는 ready Set 전체를 삭제한다.
   *
   * [주의]
   * leave_lobby.lua의 원자 처리 이후 보조 정리로 수행한다.
   * ready 정리 실패가 퇴장 자체를 실패로 되돌리면 안 되므로 예외는 로그만 남긴다.
   */
  private void cleanupReadyStatusAfterLeave(
          String code,
          String userId,
          LeaveLobbyResult leaveResult
  ) {
    try {
      if (leaveResult instanceof LeaveLobbyResult.Destroyed) {
        redisTemplate.delete(RedisKeys.lobbyReadyKey(code));
        return;
      }

      if (leaveResult instanceof LeaveLobbyResult.Left
              || leaveResult instanceof LeaveLobbyResult.Delegated) {
        redisTemplate.opsForSet().remove(RedisKeys.lobbyReadyKey(code), userId);
      }
    } catch (Exception e) {
      log.warn("퇴장 후 ready 상태 정리 실패 - lobbyCode: {}, userId: {}", code, userId, e);
    }
  }

  /**
   * 강퇴 성공 후 강퇴 대상의 ready 상태를 제거한다.
   *
   * [필요 이유]
   * 강퇴된 유저가 lobby:{code}:ready Set에 남아 있으면
   * 이후 canStart 계산이나 준비 상태 표시가 왜곡될 수 있다.
   */
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

  private KickLobbyResult parseKickLuaResult(
          String result,
          String code,
          String requesterIdentifier,
          String targetUserIdentifier
  ) {
    if (result == null) {
      return new KickLobbyResult.Error("Lua 스크립트 반환값이 null입니다.");
    }

    if (result.startsWith(RESULT_KICKED_PREFIX)) {
      String targetWsSessionId = result.substring(RESULT_KICKED_PREFIX.length());

      return new KickLobbyResult.Kicked(
              code,
              targetUserIdentifier,
              targetWsSessionId
      );
    }

    if (RESULT_LOBBY_NOT_FOUND.equals(result)) {
      return new KickLobbyResult.LobbyNotFound(code);
    }

    if (RESULT_HOST_NOT_FOUND.equals(result)) {
      return new KickLobbyResult.HostNotFound(code);
    }

    if (RESULT_FORBIDDEN.equals(result)) {
      return new KickLobbyResult.Forbidden(code, requesterIdentifier);
    }

    if (RESULT_CANNOT_KICK_SELF.equals(result)) {
      return new KickLobbyResult.CannotKickSelf(code, requesterIdentifier);
    }

    if (RESULT_TARGET_NOT_PARTICIPANT.equals(result)) {
      return new KickLobbyResult.TargetNotParticipant(code, targetUserIdentifier);
    }

    return new KickLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
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

  /**
   * Redis에서 문자열을 직접 읽어 DTO에 넣으면 MapCategory의 @JsonValue가 적용되지 않으므로,
   * 응답 생성 시점에 명시적으로 표시 값을 변환한다.
   *
   * [정책]
   * - null 또는 blank는 맵 미선택 상태로 보고 null로 반환한다.
   * - 정상 값은 MapCategory의 단일 정규화 규칙을 사용한다.
   * - 알 수 없는 값은 데이터 손상을 숨기지 않기 위해 원본 값을 반환하고 경고 로그를 남긴다.
   *
   * @param rawCategory Redis Hash에서 읽은 원본 카테고리 값
   * @return FE 응답에 사용할 카테고리 표시 값
   */
  private String toDisplayMapCategory(String rawCategory) {
    try {
      return MapCategory.toDisplayValue(rawCategory);
    } catch (IllegalArgumentException e) {
      log.warn("알 수 없는 맵 카테고리 값 - rawCategory: {}", rawCategory);
      return rawCategory;
    }
  }

  /**
   * Redis Hash의 필수 양수 정수 필드를 파싱한다.
   *
   * [사용 목적]
   * max_players처럼 로비 입장 검증에 반드시 필요한 필드는
   * 누락되거나 잘못된 값일 때 기본값으로 폴백하면 안 된다.
   *
   * [실패 처리]
   * - null
   * - 숫자 파싱 실패
   * - 0 이하
   *
   * 위 경우는 Redis 로비 데이터 손상으로 보고 500을 반환한다.
   *
   * @param value      Redis Hash에서 조회한 원시값
   * @param fieldName  Redis Hash 필드명
   * @param inviteCode 로비 초대 코드
   * @return 파싱된 양수 정수
   */
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
