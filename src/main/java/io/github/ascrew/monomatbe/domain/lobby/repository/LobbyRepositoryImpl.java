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
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<String> leaveLobbyScript;

  // Lua 스크립트 반환값 상수
  // 서비스 레이어에 노출되지 않고 이 클래스 안에서만 사용됩니다.
  private static final String RESULT_DESTROYED = "DESTROYED";
  private static final String RESULT_LEFT = "LEFT";
  private static final String RESULT_DELEGATED_PREFIX = "DELEGATED:";

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
   * Lua 스크립트 반환값을 LeaveLobbyResult 도메인 객체로 변환합니다.
   * 문자열 파싱 로직을 Repository 내부에 캡슐화하여
   * 서비스 레이어가 Redis 반환값 포맷을 알 필요가 없도록 합니다.
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
      String newHostId = result.substring(RESULT_DELEGATED_PREFIX.length());
      return new LeaveLobbyResult.Delegated(code, newHostId);
    }

    return new LeaveLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
  }

  /**
   * 공개 로비 목록을 Redis에서 직접 필터링하여 반환합니다.
   *
   * [성능 고려사항]
   * 현재 로비 수만큼 반복하여 Redis를 조회하는 N+1 구조입니다.
   * TODO: 로비 수가 증가할 경우 Redis Pipeline 또는 MGET으로 최적화 필요
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

      if (!data.isEmpty()) {
        result.add(LobbyRedisDto.builder()
                .code((String) data.get("code"))
                .hostId((String) data.get("host_user_id"))
                .title((String) data.get("title"))
                .status((String) data.get("status"))
                .build());
      }
    }

    return result;
  }
}