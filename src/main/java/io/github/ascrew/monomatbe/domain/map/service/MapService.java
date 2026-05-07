package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapSummaryResponse;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapRequest;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MapService {

    private static final Duration MAP_CACHE_TTL = Duration.ofMinutes(5);

    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵을 생성할 수 있습니다.";
    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 수정/삭제할 수 있습니다.";
    private static final String ERROR_USER_NOT_FOUND = "사용자를 찾을 수 없습니다.";
    private final QuizMapJpaRepository quizMapJpaRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public List<MapSummaryResponse> getPublicMaps() {
        String cacheKey = RedisKeys.mapPublicListKey();
        String cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            return jsonMapper.readValue(cachedValue, new TypeReference<List<MapSummaryResponse>>() {});
        }

        List<MapSummaryResponse> response = quizMapJpaRepository.findAllByIsDeletedFalseAndIsPublicTrueOrderByUpdatedAtDesc()
                .stream()
                .map(this::toSummaryResponse)
                .toList();

        redisTemplate.opsForValue().set(cacheKey, jsonMapper.writeValueAsString(response), MAP_CACHE_TTL);
        return response;
    }

    @Transactional(readOnly = true)
    public MapDetailResponse getPublicMap(Long mapId) {
        String cacheKey = RedisKeys.mapPublicDetailKey(mapId);
        String cachedValue = redisTemplate.opsForValue().get(cacheKey);
        if (cachedValue != null) {
            return jsonMapper.readValue(cachedValue, MapDetailResponse.class);
        }

        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalseAndIsPublicTrue(mapId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));

        MapDetailResponse response = toDetailResponse(quizMap);
        redisTemplate.opsForValue().set(cacheKey, jsonMapper.writeValueAsString(response), MAP_CACHE_TTL);
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
        redisTemplate.delete(List.of(
                RedisKeys.mapPublicListKey(),
                RedisKeys.mapPublicDetailKey(mapId)
        ));
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
