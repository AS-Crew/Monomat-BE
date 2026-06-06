package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapItemResponse;
import io.github.ascrew.monomatbe.domain.map.dto.ReorderMapItemsRequest;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.support.AnswerNormalizer;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;

@Service
public class MapItemService {

    private static final int DEFAULT_HINT_TIME = 15;

    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵 문제를 관리할 수 있습니다.";
    private static final String ERROR_INVALID_TIME_RANGE = "재생 구간은 시작 시간보다 종료 시간이 커야 합니다.";
    private static final String ERROR_INVALID_VIDEO_DURATION = "YouTube 영상 길이 정보가 올바르지 않습니다.";
    private static final String ERROR_START_TIME_EXCEEDS_DURATION = "재생 시작 시간은 YouTube 영상 길이보다 작아야 합니다.";
    private static final String ERROR_END_TIME_EXCEEDS_DURATION = "재생 종료 시간은 YouTube 영상 길이를 초과할 수 없습니다.";
    private static final String ERROR_DUPLICATE_ORDER = "이미 사용 중인 문제 순서입니다.";
    private static final String ERROR_NO_VALID_ANSWER = "정답은 최소 1개 이상이어야 합니다.";

    private final MapItemPersistenceService persistenceService;
    private final YoutubeValidationService youtubeValidationService;
    private final MapCacheEvictor mapCacheEvictor;
    private final JsonMapper jsonMapper;

    public MapItemService(
            MapItemPersistenceService persistenceService,
            YoutubeValidationService youtubeValidationService,
            MapCacheEvictor mapCacheEvictor,
            @Qualifier("pubSubJsonMapper") JsonMapper jsonMapper
    ) {
        this.persistenceService = persistenceService;
        this.youtubeValidationService = youtubeValidationService;
        this.mapCacheEvictor = mapCacheEvictor;
        this.jsonMapper = jsonMapper;
    }

    public List<MapItemResponse> getMapItems(Long mapId, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);

        return persistenceService.findItemsForOwnedMap(mapId, principal.userId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public MapItemResponse createMapItem(Long mapId, CreateMapItemRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);
        validateBasicTimeRange(request.startTime(), request.endTime());

        // 외부 oEmbed 호출은 트랜잭션/DB 커넥션 점유 밖에서 수행한다.
        YoutubeMetadata metadata = youtubeValidationService.validateYoutubeUrl(request.youtubeUrl());
        validateTimeRangeWithinDuration(request.startTime(), request.endTime(), metadata.durationSeconds());

        String answersJson = serializeAnswers(request.answers());
        int hintTime = request.hintTime() == null ? DEFAULT_HINT_TIME : request.hintTime();
        String hint = request.hint().trim();

        // 서비스 레벨 pre-check는 단일 요청 fast-fail 용도이고,
        // 실제 동시성 보장은 DB 레벨 (map_id, active_order_num) UNIQUE 제약이 담당한다.
        MapItem created;
        try {
            created = persistenceService.create(
                    mapId,
                    principal.userId(),
                    request,
                    metadata,
                    answersJson,
                    hint,
                    hintTime
            );
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER, e);
        }

        // 영속화 메서드가 정상 반환된 = 트랜잭션 커밋 완료된 시점에 캐시를 무효화한다.
        mapCacheEvictor.evictPublicMapCaches(mapId);
        return toResponse(created);
    }

    public MapItemResponse updateMapItem(Long mapId, Long itemId, UpdateMapItemRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);
        validateBasicTimeRange(request.startTime(), request.endTime());

        // 외부 oEmbed 호출은 트랜잭션/DB 커넥션 점유 밖에서 수행한다.
        YoutubeMetadata metadata = youtubeValidationService.validateYoutubeUrl(request.youtubeUrl());
        validateTimeRangeWithinDuration(request.startTime(), request.endTime(), metadata.durationSeconds());

        String answersJson = serializeAnswers(request.answers());
        int hintTime = request.hintTime() == null ? DEFAULT_HINT_TIME : request.hintTime();
        String hint = request.hint().trim();

        MapItem updated;
        try {
            updated = persistenceService.update(
                    mapId,
                    itemId,
                    principal.userId(),
                    request,
                    metadata,
                    answersJson,
                    hint,
                    hintTime
            );
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER, e);
        }

        mapCacheEvictor.evictPublicMapCaches(mapId);
        return toResponse(updated);
    }

    public void deleteMapItem(Long mapId, Long itemId, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);

        persistenceService.delete(mapId, itemId, principal.userId());

        mapCacheEvictor.evictPublicMapCaches(mapId);
    }

    public void reorderMapItems(Long mapId, ReorderMapItemsRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);

        try {
            persistenceService.reorder(mapId, principal.userId(), request.itemIds());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER, e);
        }

        mapCacheEvictor.evictPublicMapCaches(mapId);
    }

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }
        if (principal.userType() != UserType.REGISTERED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }
    }

    private void validateBasicTimeRange(Integer startTime, Integer endTime) {
        if (startTime == null || endTime == null || endTime <= startTime) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_TIME_RANGE);
        }
    }

    private void validateTimeRangeWithinDuration(Integer startTime, Integer endTime, Integer durationSeconds) {
        if (durationSeconds == null) {
            return;
        }

        if (durationSeconds <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_VIDEO_DURATION);
        }

        if (startTime >= durationSeconds) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_START_TIME_EXCEEDS_DURATION);
        }

        if (endTime > durationSeconds) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_END_TIME_EXCEEDS_DURATION);
        }
    }

    private String serializeAnswers(List<String> answers) {
        List<String> normalized = AnswerNormalizer.normalizeList(answers);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_NO_VALID_ANSWER);
        }
        return jsonMapper.writeValueAsString(normalized);
    }

    private List<String> deserializeAnswers(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return jsonMapper.readValue(raw, new TypeReference<List<String>>() {});
    }

    private MapItemResponse toResponse(MapItem mapItem) {
        return MapItemResponse.builder()
                .id(mapItem.getId())
                .mapId(mapItem.getMap().getId())
                .orderNum(mapItem.getOrderNum())
                .youtubeUrl(mapItem.getYoutubeUrl())
                .videoId(mapItem.getVideoId())
                .startTime(mapItem.getStartTime())
                .endTime(mapItem.getEndTime())
                .title(mapItem.getTitle())
                .artist(mapItem.getArtist())
                .thumbnailUrl(mapItem.getThumbnailUrl())
                .answers(deserializeAnswers(mapItem.getAnswers()))
                .hint(mapItem.getHint())
                .hintTime(mapItem.getHintTime())
                .createdAt(mapItem.getCreatedAt())
                .updatedAt(mapItem.getUpdatedAt())
                .build();
    }
}