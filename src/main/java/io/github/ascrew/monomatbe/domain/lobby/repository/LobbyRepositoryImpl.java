/*
 * LobbyRepository의 Redis 구현체.
 *
 * [설계 결정]
 * - StringRedisTemplate을 사용하는 이유:
 *   로비 데이터는 Redis Hash로 저장되며 Key/Value 모두 String입니다.
 *   RedisTemplate<String, Object>보다 가볍고 직렬화 오버헤드가 없습니다.
 *
 * - Lua 스크립트를 사용하는 이유:
 *   퇴장 처리 시 여러 Redis 키를 조작해야 하는데,
 *   Java 레벨에서 순차 처리하면 Race Condition이 발생할 수 있습니다.
 *   Lua 스크립트는 Redis 서버에서 원자적으로 실행되므로 이를 방지합니다.
 *
 * - Lua 반환값 파싱을 이 클래스에서 담당하는 이유:
 *   Redis 반환값의 문자열 포맷은 인프라 세부사항입니다.
 *   서비스 레이어가 "DELEGATED:" 같은 문자열 포맷을 알 필요가 없으며,
 *   파싱 책임을 Repository에 캡슐화하여 서비스는 순수한 도메인 결과만 받습니다.
 *
 * [리팩토링 변경 사항]
 * - getPublicLobbies()에서 Redis Hash 필드 키를 문자열 리터럴 대신 RedisKeys.FIELD_* 상수로 교체
 *   → 오타 방지 및 저장/조회 필드명 일관성 보장
 * - 누락된 DTO 필드(mapId, maxPlayers, isPrivate) 매핑 추가
 *   → 클라이언트에 완전한 로비 정보 전달
 * - Redis String → 숫자 타입 파싱 방어 메서드 추가 (parseNullableLong, parseNullableInt)
 *   → ClassCastException 및 NumberFormatException 방지
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
  // 서비스 레이어에 노출되지 않고 이 클래스 안에서만 사용됩니다.
  // =========================================================

  /** Lua 스크립트 반환값 — 로비 폭파 (모든 인원 퇴장) */
  private static final String RESULT_DESTROYED = "DESTROYED";

  /** Lua 스크립트 반환값 — 일반 유저 퇴장 */
  private static final String RESULT_LEFT = "LEFT";

  /** Lua 스크립트 반환값 접두사 — 방장 위임 (뒤에 새 방장 ID가 붙음) */
  private static final String RESULT_DELEGATED_PREFIX = "DELEGATED:";

  // =========================================================
  // 공개 메서드
  // =========================================================

  /**
   * 해당 코드의 로비가 Redis에 존재하는지 확인합니다.
   *
   * @param code 로비 초대 코드
   * @return 로비 Hash 키 존재 여부
   */
  @Override
  public boolean existsByCode(String code) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.lobbyKey(code)));
  }

  /**
   * 해당 유저가 해당 로비의 참여자 Set에 포함되어 있는지 확인합니다.
   *
   * @param code   로비 초대 코드
   * @param userId 사용자 식별자
   * @return 참여자 여부
   */
  @Override
  public boolean isParticipant(String code, String userId) {
    return Boolean.TRUE.equals(
            redisTemplate.opsForSet().isMember(RedisKeys.lobbyParticipantsKey(code), userId)
    );
  }

  /**
   * Lua 스크립트를 실행하여 퇴장 처리를 원자적으로 수행하고
   * 결과를 도메인 객체(LeaveLobbyResult)로 변환하여 반환합니다.
   *
   * [KEYS 구성]
   * KEYS[1] = lobby:{code}              — 로비 메타 정보 Hash
   * KEYS[2] = lobby:{code}:participants — 참여자 Set
   * KEYS[3] = lobby:{code}:order        — 입장 순서 List
   * KEYS[4] = lobby:public              — 전역 공개 로비 Set
   *
   * [ARGV 구성]
   * ARGV[1] = userId  — 퇴장하는 유저 식별자
   * ARGV[2] = code    — 로비 코드
   *
   * @param code   퇴장 처리 대상 로비 코드
   * @param userId 퇴장하는 유저 식별자
   * @return LeaveLobbyResult (Destroyed | Delegated | Left | Error)
   */
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

  /**
   * Redis에서 공개 로비 목록을 조회하여 반환합니다.
   *
   * [동작 순서]
   * 1. lobby:public Set에서 공개 로비 코드 목록을 가져옵니다.
   * 2. 각 코드로 lobby:{code} Hash를 조회하여 DTO로 변환합니다.
   * 3. Hash 데이터가 없는 코드(좀비 항목)는 건너뜁니다.
   *
   * [성능 고려사항]
   * 현재 로비 수만큼 반복하여 Redis를 조회하는 N+1 구조입니다.
   * TODO: 로비 수가 증가할 경우 Redis Pipeline 또는 MGET으로 최적화 필요
   *
   * @return 현재 공개 상태인 로비 DTO 목록
   */
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

      // lobby:public에는 있지만 실제 Hash가 삭제된 좀비 항목 방어
      if (data.isEmpty()) continue;

      result.add(LobbyRedisDto.builder()
              // [수정] 문자열 리터럴 → RedisKeys.FIELD_* 상수로 교체 (오타 방지)
              .code((String) data.get(RedisKeys.FIELD_CODE))
              .hostId((String) data.get(RedisKeys.FIELD_HOST_USER_ID))
              .title((String) data.get(RedisKeys.FIELD_TITLE))
              .status((String) data.get(RedisKeys.FIELD_STATUS))

              // [수정] 누락된 필드 3개 추가
              // Redis는 모든 값을 String으로 저장하므로 숫자 타입은 안전하게 파싱
              .mapId(parseNullableLong(data.get(RedisKeys.FIELD_MAP_ID)))
              .maxPlayers(parseNullableInt(data.get(RedisKeys.FIELD_MAX_PLAYERS)))
              // "true" / "false" 문자열을 Boolean으로 변환
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
   * Lua 스크립트 반환값을 LeaveLobbyResult 도메인 객체로 변환합니다.
   *
   * [캡슐화 이유]
   * "DELEGATED:", "DESTROYED" 같은 Redis 반환 포맷은 인프라 세부사항입니다.
   * 파싱 책임을 이 Repository 내부에 숨겨 서비스 레이어가
   * Redis 반환 포맷을 알 필요 없도록 합니다.
   *
   * @param result Lua 스크립트 반환값 문자열
   * @param code   퇴장 처리 대상 로비 코드
   * @param userId 퇴장하는 유저 식별자
   * @return 파싱된 LeaveLobbyResult 도메인 객체
   */
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
      // "DELEGATED:{newHostId}" 형태에서 새 방장 ID 추출
      String newHostId = result.substring(RESULT_DELEGATED_PREFIX.length());
      return new LeaveLobbyResult.Delegated(code, newHostId);
    }

    return new LeaveLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
  }

  /**
   * Redis에서 조회한 Object 값을 Long으로 안전하게 변환합니다.
   *
   * Redis는 모든 Hash 값을 String으로 저장하므로,
   * Long 타입 필드는 반드시 String → Long 파싱이 필요합니다.
   * 값이 null이거나 숫자가 아닌 경우 null을 반환하여
   * NumberFormatException이 서비스 레이어로 전파되는 것을 방지합니다.
   *
   * @param value Redis Hash에서 조회한 원시 값 (null 허용)
   * @return 변환된 Long, 변환 불가 시 null
   */
  private Long parseNullableLong(Object value) {
    if (value == null) return null;
    try {
      return Long.parseLong((String) value);
    } catch (NumberFormatException e) {
      log.warn("Redis Hash 필드 Long 파싱 실패 - 값: {}", value);
      return null;
    }
  }

  /**
   * Redis에서 조회한 Object 값을 Integer로 안전하게 변환합니다.
   *
   * Redis는 모든 Hash 값을 String으로 저장하므로,
   * Integer 타입 필드는 반드시 String → Integer 파싱이 필요합니다.
   * 값이 null이거나 숫자가 아닌 경우 null을 반환하여
   * NumberFormatException이 서비스 레이어로 전파되는 것을 방지합니다.
   *
   * @param value Redis Hash에서 조회한 원시 값 (null 허용)
   * @return 변환된 Integer, 변환 불가 시 null
   */
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