package io.github.ascrew.monomatbe.repository;

import io.github.ascrew.monomatbe.dto.LobbyRedisDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실시간 게임 데이터 처리를 위해 Redis와 직접 통신하는 구현체.
 * RDBMS(MySQL)의 트랜잭션 부하를 피하고, Lettuce + Virtual Threads 기반의 I/O를 담당
 */

@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<String> leaveLobbyScript; // 등록해둔 Lua 스크립트 빈 주입

  @Override
  public boolean existsByCode(String code) {
    return Boolean.TRUE.equals(redisTemplate.hasKey("lobby:" + code));
  }

  @Override
  public boolean isParticipant(String code, String userId) {
    // Lua 스크립트의 KEYS 파라미터에 매핑될 키 목록
    return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember("lobby:" + code + ":participants", userId));
  }

  /**
   * 방장 위임 및 로비 폭파를 포함한 퇴장 처리 (Atomic)
   * Java 레벨에서 순차적으로 처리할 때 발생할 수 있는 Race Condition(경쟁 상태)을 방지하기 위해
   * 여러 개의 키를 조작하는 로직을 단일 Lua 스크립트로 묶어 서버로 전송
   */
  @Override
  public String executeLeaveLobbyProcess(String code, String userId) {
    // Lua 스크립트의 KEYS 파라미터에 매핑될 키 목록
    List<String> keys = List.of(
            "lobby:" + code,
            "lobby:" + code + ":participants",
            "lobby:" + code + ":order",
            "lobby:public"
    );
    // 스크립트, 키 목록, ARGV에 들어갈 인자 (userId, code) 순으로 실행
    return redisTemplate.execute(leaveLobbyScript, keys, userId, code);
  }

  // 고속 로비 리스트 조회 (공개 로비만 필터링)
  // DB를 조회하지 않고 Redis 메모리에서 직접 공개 방 목록을 필터링한다.
  @Override
  public List<LobbyRedisDto> getPublicLobbies() {
    Set<String> publicLobbyCodes = redisTemplate.opsForSet().members("lobby:public");
    if (publicLobbyCodes == null || publicLobbyCodes.isEmpty()) {
      return new ArrayList<>();
    }

    List<LobbyRedisDto> result = new ArrayList<>();
    // TODO: 방 개수가 매우 많아질 경우, 반복문 순회(N+1) 대신 Redis Pipeline이나 MGET으로 최적화 필요
    for (String code : publicLobbyCodes) {
      Map<Object, Object> data = redisTemplate.opsForHash().entries("lobby:" + code);
      if (!data.isEmpty()) {
        result.add(LobbyRedisDto.builder()
                .code((String) data.get("code"))
                .hostId((String) data.get("host_user_id"))
                .title((String) data.get("title"))
                .status((String) data.get("status"))
                // 필요한 필드들 매핑
                .build());
      }
    }
    return result;
  }
}