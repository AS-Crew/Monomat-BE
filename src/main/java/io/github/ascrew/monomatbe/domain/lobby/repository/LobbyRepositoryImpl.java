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
 */
package io.github.ascrew.monomatbe.domain.lobby.repository;

import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<String> leaveLobbyScript;

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
   * Lua 스크립트를 실행하여 퇴장 처리를 원자적으로 수행합니다.
   *
   * KEYS[1] = lobby:{code}              — 로비 메타 정보 Hash
   * KEYS[2] = lobby:{code}:participants — 참여자 Set
   * KEYS[3] = lobby:{code}:order        — 입장 순서 List
   * KEYS[4] = lobby:public              — 전역 공개 로비 Set
   * ARGV[1] = userId                    — 퇴장하는 유저 ID
   * ARGV[2] = code                      — 로비 코드
   */
  @Override
  public String executeLeaveLobbyProcess(String code, String userId) {
    List<String> keys = List.of(
            RedisKeys.lobbyKey(code),
            RedisKeys.lobbyParticipantsKey(code),
            "lobby:" + code + ":order",
            "lobby:public"
    );
    return redisTemplate.execute(leaveLobbyScript, keys, userId, code);
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
    Set<String> publicLobbyCodes = redisTemplate.opsForSet().members(RedisKeys.LOBBY_PUBLIC);

    if (publicLobbyCodes == null || publicLobbyCodes.isEmpty()) {
      return new ArrayList<>();
    }

    List<LobbyRedisDto> result = new ArrayList<>();

    for (String code : publicLobbyCodes) {
      Map<Object, Object> data = redisTemplate.opsForHash().entries(RedisKeys.lobbyKey(code));

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