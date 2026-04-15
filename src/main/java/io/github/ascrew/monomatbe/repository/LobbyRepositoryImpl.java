package io.github.ascrew.monomatbe.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LobbyRepositoryImpl implements LobbyRepository {

  private final StringRedisTemplate redisTemplate;

  @Override
  public boolean existsById(String id) {
    // 구현 예정
    // Redis key 예시: lobby:{id}
    // return Boolean.TRUE.equals(redisTemplate.hasKey("lobby:" + id));

    return true;
  }

  @Override
  public boolean isParticipant(String code, String userId) {
    // 구현 예정
    // Redis Set key 예시: lobby:{id}:participants
    /**
    Boolean isMember =
        redisTemplate.opsForSet().isMember("lobby:" + code + ":participants", userId);
    return Boolean.TRUE.equals(isMember);
    **/

    return true;
  }
}