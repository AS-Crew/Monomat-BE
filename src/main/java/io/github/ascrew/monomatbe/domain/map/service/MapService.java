package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapSummaryResponse;
import io.github.ascrew.monomatbe.domain.map.dto.PublicMapPageResponse;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.MapSortType;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapSpecification;
import org.springframework.data.jpa.domain.Specification;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
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
    private final MapPublicationValidator publicationValidator;
    private final MapCacheEvictor mapCacheEvictor;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public MapService(
            QuizMapJpaRepository quizMapJpaRepository,
            UserRepository userRepository,
            MapPublicationValidator publicationValidator,
            MapCacheEvictor mapCacheEvictor,
            StringRedisTemplate redisTemplate,
            @Qualifier("cacheJsonMapper") JsonMapper jsonMapper
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.userRepository = userRepository;
        this.publicationValidator = publicationValidator;
        this.mapCacheEvictor = mapCacheEvictor;
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    // 공개된 맵 목록을 검색 조건과 함께 페이징하여 조회합니다.
    // keyword 없는 경우에만 Redis 캐싱을 적용합니다 (keyword 검색은 무한 조합으로 캐시 효율이 낮음).
    @Transactional(readOnly = true)
    public PublicMapPageResponse getPublicMaps(
            Integer page, Integer size, String keyword, MapCategory category, MapSortType sort
    ) {
        int normalizedPage = page == null || page < 0 ? DEFAULT_PAGE : page;
        int normalizedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        MapSortType normalizedSort = sort == null ? MapSortType.NEWEST : sort;

        String normalizedKeyword = (keyword == null || keyword.isBlank())
                ? null
                : keyword.trim().toLowerCase();

        if (normalizedKeyword != null) {
            return queryPublicMaps(normalizedKeyword, category, normalizedPage, normalizedSize, normalizedSort);
        }

        String version = getPublicListCacheVersion();
        String cacheKey = RedisKeys.mapPublicListKey(
                version,
                null,
                category == null ? null : category.name(),
                normalizedSort.name(),
                normalizedPage,
                normalizedSize
        );

        try {
            String cachedValue = redisTemplate.opsForValue().get(cacheKey);
            if (cachedValue != null) {
                try {
                    return jsonMapper.readValue(
                            cachedValue,
                            new TypeReference<PublicMapPageResponse>() {
                            }
                    );
                } catch (Exception e) {
                    log.warn("공개 맵 목록 캐시 역직렬화 실패 - key: {}. 캐시 삭제 후 DB fallback", cacheKey, e);
                    safeDeleteCache(cacheKey);
                }
            }
        } catch (Exception e) {
            log.warn("공개 맵 목록 캐시 조회 실패 - key: {}. DB fallback", cacheKey, e);
        }

        PublicMapPageResponse response =
                queryPublicMaps(null, category, normalizedPage, normalizedSize, normalizedSort);

        safeWriteCache(cacheKey, response);

        return response;
    }

    private PublicMapPageResponse queryPublicMaps(
            String keyword, MapCategory category, int page, int size, MapSortType sort
    ) {
        Specification<QuizMap> spec = Specification
                .where(QuizMapSpecification.isPublicAndNotDeleted())
                .and(QuizMapSpecification.withKeyword(keyword))
                .and(QuizMapSpecification.withCategory(category));

        Pageable pageable = PageRequest.of(page, size, toSort(sort));
        Page<QuizMap> pageResult = quizMapJpaRepository.findAll(spec, pageable);

        List<MapSummaryResponse> content = pageResult.getContent()
                .stream()
                .map(this::toSummaryResponse)
                .toList();

        return PublicMapPageResponse.builder()
                .content(content)
                .page(pageResult.getNumber())
                .size(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .hasNext(pageResult.hasNext())
                .build();
    }

    // 로그인한 사용자의 맵 목록(공개/비공개 모두, 삭제 제외)을 페이징하여 조회합니다.
    // 개인 데이터이므로 Redis 캐시를 적용하지 않습니다.
    @Transactional(readOnly = true)
    public PublicMapPageResponse getMyMaps(Integer page, Integer size, CustomPrincipal principal) {
        validatePrincipal(principal);

        int normalizedPage = page == null || page < 0 ? DEFAULT_PAGE : page;
        int normalizedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        Specification<QuizMap> spec =
                QuizMapSpecification.ownedByAndNotDeleted(principal.userId());

        Pageable pageable = PageRequest.of(
                normalizedPage,
                normalizedSize,
                Sort.by(Sort.Direction.DESC, "updatedAt")
        );

        Page<QuizMap> pageResult = quizMapJpaRepository.findAll(spec, pageable);

        List<MapSummaryResponse> content = pageResult.getContent()
                .stream()
                .map(this::toSummaryResponse)
                .toList();

        return PublicMapPageResponse.builder()
                .content(content)
                .page(pageResult.getNumber())
                .size(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .hasNext(pageResult.hasNext())
                .build();
    }

    @Transactional(readOnly = true)
    public MapDetailResponse getPublicMap(Long mapId) {
        String cacheKey = RedisKeys.mapPublicDetailKey(mapId);

        // 캐시 조회/역직렬화 실패는 DB fallback으로 처리합니다.
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
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_MAP_NOT_FOUND
                ));

        MapDetailResponse response = toDetailResponse(quizMap);
        safeWriteCache(cacheKey, response);

        return response;
    }

    @Transactional
    public MapDetailResponse createMap(CreateMapRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);

        User owner = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_USER_NOT_FOUND
                ));

        if (owner.getUserType() != UserType.REGISTERED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_REGISTERED_ONLY
            );
        }

        // 생성 시점에는 아이템이 0개이므로 공개 조건을 만족할 수 없다.
        // isPublic=true 요청은 의도만 pendingPublic 으로 보존하고, 첫 유효 아이템 추가 시 자동 공개로 전환된다.
        QuizMap created = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title(request.title())
                .description(request.description())
                .category(request.category())
                .isPublic(false)
                .pendingPublic(request.isPublic())
                .build());

        mapCacheEvictor.evictPublicMapCaches(created.getId());

        return toDetailResponse(created);
    }

    @Transactional
    public MapDetailResponse updateMap(Long mapId, UpdateMapRequest request, CustomPrincipal principal) {
        validatePrincipal(principal);

        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_MAP_NOT_FOUND
                ));

        validateOwnership(quizMap, principal);

        quizMap.update(
                request.title(),
                request.description(),
                request.category()
        );

        applyPublicationChange(quizMap, request.isPublic());

        mapCacheEvictor.evictPublicMapCaches(quizMap.getId());

        return toDetailResponse(quizMap);
    }

    // 명시적인 공개 상태 변경 요청을 처리한다.
    // 비공개 → 공개 전이일 때만 검증한다. 이미 공개 상태인 맵의 메타데이터 수정에서
    // 재검증을 강제하면 데이터 오염 시 단순 수정도 409로 막혀 복구 경로가 좁아진다.
    // 공개 상태의 무결성은 MapItemPersistenceService.applyPublicationAutoFlip 가 보장한다.
    private void applyPublicationChange(QuizMap quizMap, boolean requestedPublic) {
        if (requestedPublic) {
            if (!Boolean.TRUE.equals(quizMap.getIsPublic())) {
                publicationValidator.requirePublishable(quizMap.getId());
                quizMap.markAsPublished();
            }
        } else {
            quizMap.markAsUnpublished(false);
        }
    }

    @Transactional
    public void deleteMap(Long mapId, CustomPrincipal principal) {
        validatePrincipal(principal);

        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_MAP_NOT_FOUND
                ));

        validateOwnership(quizMap, principal);

        quizMap.softDelete();
        mapCacheEvictor.evictPublicMapCaches(quizMap.getId());
    }

    private void validatePrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    ERROR_INVALID_PRINCIPAL
            );
        }
    }

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        validatePrincipal(principal);

        if (principal.userType() != UserType.REGISTERED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_REGISTERED_ONLY
            );
        }
    }

    private void validateOwnership(QuizMap quizMap, CustomPrincipal principal) {
        if (!quizMap.getOwner().getId().equals(principal.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_MAP_FORBIDDEN
            );
        }
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

    private Sort toSort(MapSortType sort) {
        return switch (sort) {
            case NEWEST     -> Sort.by(Sort.Direction.DESC, "updatedAt");
            case OLDEST     -> Sort.by(Sort.Direction.ASC,  "updatedAt");
            case MOST_SONGS -> Sort.by(Sort.Direction.DESC, "numOfSong");
            case TITLE_ASC  -> Sort.by(Sort.Direction.ASC,  "title");
        };
    }

    private MapSummaryResponse toSummaryResponse(QuizMap quizMap) {
        return MapSummaryResponse.builder()
                .mapId(quizMap.getId())
                .title(quizMap.getTitle())
                .category(quizMap.getCategory())
                .numOfSong(quizMap.getNumOfSong())
                .totalPlayTime(quizMap.getTotalPlayTime())
                .isPublic(Boolean.TRUE.equals(quizMap.getIsPublic()))
                .pendingPublic(Boolean.TRUE.equals(quizMap.getPendingPublic()))
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
                .pendingPublic(Boolean.TRUE.equals(quizMap.getPendingPublic()))
                .createdAt(quizMap.getCreatedAt())
                .updatedAt(quizMap.getUpdatedAt())
                .build();
    }
}