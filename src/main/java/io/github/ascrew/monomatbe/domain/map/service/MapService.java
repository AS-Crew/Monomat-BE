package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapSummaryResponse;
import io.github.ascrew.monomatbe.domain.map.dto.PublicMapPageResponse;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapRequest;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MapService {

    private static final Duration MAP_CACHE_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵을 생성할 수 있습니다.";
    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 수정/삭제할 수 있습니다.";
    private static final String ERROR_USER_NOT_FOUND = "사용자를 찾을 수 없습니다.";
    private final QuizMapJpaRepository quizMapJpaRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    // 공개된 맵 목록을 페이징하여 조회 (Redis 캐싱 적용)
    @Transactional(readOnly = true)
    public PublicMapPageResponse getPublicMaps(Integer page, Integer size) {
        // 파라미터 정규화 및 유효성 검사
        int normalizedPage = page == null || page < 0 ? DEFAULT_PAGE : page;
        int normalizedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        // 캐시 키 생성 및 조회
        String version = getPublicListCacheVersion();
        String cacheKey = RedisKeys.mapPublicListKey(version, normalizedPage, normalizedSize);
        // 캐시 조회/역직렬화 실패는 DB fallback으로 처리한다.
        try {
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            if (cachedValue != null) {
                try {
                    return jsonMapper.readValue(cachedValue, new TypeReference<PublicMapPageResponse>() {});
                } catch (Exception e) {
                    log.warn("공개 맵 목록 캐시 역직렬화 실패 - key: {}. 캐시 삭제 후 DB fallback", cacheKey, e);
                    safeDeleteCache(cacheKey);
                }
            }
        } catch (Exception e) {
            log.warn("공개 맵 목록 캐시 조회 실패 - key: {}. DB fallback", cacheKey, e);
        }

        // 캐시 미스시 DB에서 데이터 조회
        Pageable pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Direction.DESC, "updatedAt")
        );
        Page<QuizMap> pageResult = quizMapJpaRepository.findAllByIsDeletedFalseAndIsPublicTrue(pageable);
        List<MapSummaryResponse> content = pageResult.getContent()
                .stream()
                .map(this::toSummaryResponse)
                .toList();

        PublicMapPageResponse response = PublicMapPageResponse.builder()
                .content(content)
                .page(pageResult.getNumber())
                .size(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .hasNext(pageResult.hasNext())
                .build();

        safeWriteCache(cacheKey, response);
        return response;
    }

    @Transactional(readOnly = true)
    public MapDetailResponse getPublicMap(Long mapId) {
        String cacheKey = RedisKeys.mapPublicDetailKey(mapId);
        // 캐시 조회/역직렬화 실패는 DB fallback으로 처리한다.
        try {
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            if (cachedValue != null) {
                try {
                    return jsonMapper.readValue(cachedValue, MapDetailResponse.class);
                } catch (Exception e) {
                    log.warn("공개 맵 단건 캐시 역직렬화 실패 - key: {}. 캐시 삭제 후 DB fallback", cacheKey, e);
                    safeDeleteCache(cacheKey);
                }
            }
        } catch (Exception e) {
            log.warn("공개 맵 단건 캐시 조회 실패 - key: {}. DB fallback", cacheKey, e);
        }

        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalseAndIsPublicTrue(mapId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));

        MapDetailResponse response = toDetailResponse(quizMap);
        safeWriteCache(cacheKey, response);
        return response;
    }

    @Transactional
    public MapDetailResponse createMap(CreateMapRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);

        User owner = userRepository.findById(principal.userId())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_USER_NOT_FOUND));
        if (owner.getUserType() != UserType.REGISTERED) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }

        QuizMap created = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title(request.title())
                .description(request.description())
                .category(request.category())
                .isPublic(request.isPublic())
                .build());

        evictMapCache(created.getId());
        return toDetailResponse(created);
    }

    @Transactional
    public MapDetailResponse updateMap(Long mapId, UpdateMapRequest request, CustomPrincipal principal) {
        validatePrincipal(principal);

        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));

        validateOwnership(quizMap, principal);
        quizMap.update(request.title(), request.description(), request.category(), request.isPublic());

        evictMapCache(quizMap.getId());
        return toDetailResponse(quizMap);
    }

    @Transactional
    public void deleteMap(Long mapId, CustomPrincipal principal) {
        validatePrincipal(principal);

        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));

        validateOwnership(quizMap, principal);
        quizMap.softDelete();
        evictMapCache(quizMap.getId());
    }

    private void validatePrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }
    }

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        validatePrincipal(principal);
        if (principal.userType() != UserType.REGISTERED) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }
    }

    private void validateOwnership(QuizMap quizMap, CustomPrincipal principal) {
        if (!quizMap.getOwner().getId().equals(principal.userId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN, ERROR_MAP_FORBIDDEN);
        }
    }

    private void evictMapCache(Long mapId) {
        redisTemplate.opsForValue().increment(RedisKeys.mapPublicListVersionKey());
        redisTemplate.delete(RedisKeys.mapPublicDetailKey(mapId));
    }

    private String getPublicListCacheVersion() {
        String key = RedisKeys.mapPublicListVersionKey();
        try {
            String version = redisTemplate.opsForValue().get(key);
            if (version != null) {
                return version;
            }

            Boolean set = redisTemplate.opsForValue().setIfAbsent(key, "1");
            if (Boolean.TRUE.equals(set)) {
                return "1";
            }

            String fallback = redisTemplate.opsForValue().get(key);
            return fallback != null ? fallback : "1";
        } catch (Exception e) {
            log.warn("공개 맵 목록 캐시 버전 조회/초기화 실패 - key: {}. 기본 버전으로 진행", key, e);
            return "1";
        }
    }

    private void safeWriteCache(String key, Object payload) {
        try {
            String serialized = jsonMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(key, serialized, MAP_CACHE_TTL);
        } catch (Exception e) {
            log.warn("맵 캐시 저장 실패 - key: {}. 응답은 정상 반환", key, e);
        }
    }

    private void safeDeleteCache(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("손상 캐시 삭제 실패 - key: {}", key, e);
        }
    }

    private MapSummaryResponse toSummaryResponse(QuizMap quizMap) {
        return MapSummaryResponse.builder()
                .id(quizMap.getId())
                .title(quizMap.getTitle())
                .category(quizMap.getCategory())
                .numOfSong(quizMap.getNumOfSong())
                .totalPlayTime(quizMap.getTotalPlayTime())
                .isPublic(Boolean.TRUE.equals(quizMap.getIsPublic()))
                .ownerId(quizMap.getOwner().getId())
                .build();
    }

    private MapDetailResponse toDetailResponse(QuizMap quizMap) {
        return MapDetailResponse.builder()
                .id(quizMap.getId())
                .ownerId(quizMap.getOwner().getId())
                .title(quizMap.getTitle())
                .description(quizMap.getDescription())
                .category(quizMap.getCategory())
                .numOfSong(quizMap.getNumOfSong())
                .totalPlayTime(quizMap.getTotalPlayTime())
                .isPublic(Boolean.TRUE.equals(quizMap.getIsPublic()))
                .createdAt(quizMap.getCreatedAt())
                .updatedAt(quizMap.getUpdatedAt())
                .build();
    }
}
