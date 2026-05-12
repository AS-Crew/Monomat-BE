package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MapCacheEvictor {

    private final StringRedisTemplate redisTemplate;

    public void evictPublicMapCaches(Long mapId) {
        try {
            redisTemplate.opsForValue().increment(RedisKeys.mapPublicListVersionKey());
        } catch (Exception e) {
            log.warn("공개 맵 목록 캐시 버전 무효화 실패 - key: {}",
                    RedisKeys.mapPublicListVersionKey(), e);
        }

        String detailKey = RedisKeys.mapPublicDetailKey(mapId);
        try {
            redisTemplate.delete(detailKey);
        } catch (Exception e) {
            log.warn("공개 맵 단건 캐시 무효화 실패 - key: {}", detailKey, e);
        }
    }
}
