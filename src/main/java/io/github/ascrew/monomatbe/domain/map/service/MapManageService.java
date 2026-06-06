package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.MapItemPolicy;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapItemResponse;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.map.support.AnswerNormalizer;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MapManageService {

    private static final int DEFAULT_HINT_TIME = 15;

    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵을 관리할 수 있습니다.";
    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 수정할 수 있습니다.";
    private static final String ERROR_MAP_ITEM_NOT_FOUND = "문제를 찾을 수 없습니다.";
    private static final String ERROR_INVALID_TIME_RANGE = "재생 구간은 시작 시간보다 종료 시간이 커야 합니다.";
    private static final String ERROR_NEGATIVE_START_TIME = "재생 시작 시간은 0초 이상이어야 합니다.";
    private static final String ERROR_INVALID_VIDEO_DURATION = "YouTube 영상 길이 정보가 올바르지 않습니다.";
    private static final String ERROR_START_TIME_EXCEEDS_DURATION = "재생 시작 시간은 YouTube 영상 길이보다 작아야 합니다.";
    private static final String ERROR_END_TIME_EXCEEDS_DURATION = "재생 종료 시간은 YouTube 영상 길이를 초과할 수 없습니다.";
    private static final String ERROR_DUPLICATE_ORDER = "중복된 문제 순서가 있습니다.";
    private static final String ERROR_INVALID_ORDER_SEQUENCE = "문제 순서는 1부터 문제 수까지 중복 없이 지정해야 합니다.";
    private static final String ERROR_DUPLICATE_ITEM_ID = "중복된 문제 ID가 있습니다.";
    private static final String ERROR_ITEM_DELETE_CONFLICT = "수정할 문제와 삭제할 문제가 중복되었습니다.";
    private static final String ERROR_MISSING_ACTIVE_ITEM = "기존 활성 문제는 수정 목록 또는 삭제 목록에 모두 포함되어야 합니다.";
    private static final String ERROR_INVALID_ITEM_ID = "현재 맵에 속하지 않는 문제 ID가 포함되어 있습니다.";
    private static final String ERROR_NO_VALID_ANSWER = "정답은 최소 1개 이상이어야 합니다.";
    private static final String ERROR_MAP_ITEM_LIMIT_EXCEEDED =
            "한 맵에 등록할 수 있는 문제는 최대 " + MapItemPolicy.MAX_ITEMS_PER_MAP + "개입니다.";
    private static final String ERROR_DUPLICATE_ACTIVE_ORDER = "이미 사용 중인 문제 순서입니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final YoutubeValidationService youtubeValidationService;
    private final MapPublicationValidator publicationValidator;
    private final MapCacheEvictor mapCacheEvictor;
    private final JsonMapper jsonMapper;

    public MapManageService(
            QuizMapJpaRepository quizMapJpaRepository,
            MapItemJpaRepository mapItemJpaRepository,
            YoutubeValidationService youtubeValidationService,
            MapPublicationValidator publicationValidator,
            MapCacheEvictor mapCacheEvictor,
            @Qualifier("pubSubJsonMapper") JsonMapper jsonMapper
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.youtubeValidationService = youtubeValidationService;
        this.publicationValidator = publicationValidator;
        this.mapCacheEvictor = mapCacheEvictor;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public ManageMapResponse updateManagedMap(
            Long mapId,
            ManageMapRequest request,
            CustomPrincipal principal
    ) {
        validateRegisteredPrincipal(principal);
        validateManageRequest(request);

        QuizMap quizMap = getOwnedMapForWriteOrThrow(mapId, principal.userId());

        List<MapItem> activeItems = mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId);
        validateItemIdentity(activeItems, request);

        List<PreparedManageItem> preparedItems = prepareItems(request.items());

        try {
            // 기존 활성 문제의 orderNum을 모두 음수로 밀어 최종 순서 적용 중 UNIQUE 충돌을 방지한다.
            // 이 쿼리는 clearAutomatically=true 이므로 이후 엔티티는 반드시 재조회한다.
            mapItemJpaRepository.setTemporaryOrderNums(mapId);

            quizMap = getOwnedMapForWriteOrThrow(mapId, principal.userId());
            Map<Long, MapItem> activeItemById = mapItemJpaRepository
                    .findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId)
                    .stream()
                    .collect(Collectors.toMap(MapItem::getId, Function.identity()));

            quizMap.update(
                    request.title(),
                    request.description(),
                    request.category()
            );

            for (Long deletedItemId : normalizeDeletedItemIds(request.deletedItemIds())) {
                MapItem mapItem = activeItemById.get(deletedItemId);
                if (mapItem == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ITEM_ID);
                }
                mapItem.softDelete();
            }

            for (PreparedManageItem preparedItem : preparedItems) {
                ManageMapItemRequest itemRequest = preparedItem.request();

                if (itemRequest.id() == null) {
                    mapItemJpaRepository.save(MapItem.builder()
                            .map(quizMap)
                            .orderNum(itemRequest.orderNum())
                            .youtubeUrl(itemRequest.youtubeUrl().trim())
                            .videoId(preparedItem.metadata().videoId())
                            .startTime(itemRequest.startTime())
                            .endTime(itemRequest.endTime())
                            .title(preparedItem.metadata().title())
                            .artist(preparedItem.metadata().artist())
                            .thumbnailUrl(preparedItem.metadata().thumbnailUrl())
                            .answers(preparedItem.answersJson())
                            .hint(preparedItem.hint())
                            .hintTime(preparedItem.hintTime())
                            .build());
                    continue;
                }

                MapItem mapItem = activeItemById.get(itemRequest.id());
                if (mapItem == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ITEM_ID);
                }

                mapItem.update(
                        itemRequest.orderNum(),
                        itemRequest.youtubeUrl().trim(),
                        preparedItem.metadata().videoId(),
                        itemRequest.startTime(),
                        itemRequest.endTime(),
                        preparedItem.metadata().title(),
                        preparedItem.metadata().artist(),
                        preparedItem.metadata().thumbnailUrl(),
                        preparedItem.answersJson(),
                        preparedItem.hint(),
                        preparedItem.hintTime()
                );
            }

            recalculateMapMetadata(quizMap, preparedItems);
            applyPublicationChange(quizMap, request.isPublic());

            mapItemJpaRepository.flush();

            List<MapItem> latestItems =
                    mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId);

            mapCacheEvictor.evictPublicMapCaches(mapId);

            return ManageMapResponse.builder()
                    .map(toMapDetailResponse(quizMap))
                    .items(latestItems.stream().map(this::toMapItemResponse).toList())
                    .build();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ACTIVE_ORDER, e);
        }
    }

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        if (principal.userType() != UserType.REGISTERED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }
    }

    private QuizMap getOwnedMapForWriteOrThrow(Long mapId, Long ownerId) {
        return quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(mapId, ownerId)
                .orElseThrow(() -> {
                    if (quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId).isEmpty()) {
                        return new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND);
                    }
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_MAP_FORBIDDEN);
                });
    }

    private void validateManageRequest(ManageMapRequest request) {
        if (request.items().size() > MapItemPolicy.MAX_ITEMS_PER_MAP) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_MAP_ITEM_LIMIT_EXCEEDED);
        }

        validateOrderNumbers(request.items());
        validateDuplicateItemIds(request.items());
    }

    private void validateOrderNumbers(List<ManageMapItemRequest> items) {
        Set<Integer> orderNumbers = new HashSet<>();

        for (ManageMapItemRequest item : items) {
            if (!orderNumbers.add(item.orderNum())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_DUPLICATE_ORDER);
            }
        }

        for (int orderNum = 1; orderNum <= items.size(); orderNum++) {
            if (!orderNumbers.contains(orderNum)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ORDER_SEQUENCE);
            }
        }
    }

    private void validateDuplicateItemIds(List<ManageMapItemRequest> items) {
        Set<Long> ids = new HashSet<>();

        for (ManageMapItemRequest item : items) {
            if (item.id() == null) {
                continue;
            }

            if (!ids.add(item.id())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_DUPLICATE_ITEM_ID);
            }
        }
    }

    private void validateItemIdentity(List<MapItem> activeItems, ManageMapRequest request) {
        Set<Long> activeItemIds = activeItems.stream()
                .map(MapItem::getId)
                .collect(Collectors.toSet());

        Set<Long> requestItemIds = request.items()
                .stream()
                .map(ManageMapItemRequest::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> deletedItemIds = normalizeDeletedItemIds(request.deletedItemIds());

        for (Long requestItemId : requestItemIds) {
            if (!activeItemIds.contains(requestItemId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ITEM_ID);
            }
        }

        for (Long deletedItemId : deletedItemIds) {
            if (!activeItemIds.contains(deletedItemId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ITEM_ID);
            }
        }

        Set<Long> duplicated = new HashSet<>(requestItemIds);
        duplicated.retainAll(deletedItemIds);
        if (!duplicated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_ITEM_DELETE_CONFLICT);
        }

        Set<Long> coveredItemIds = new HashSet<>(requestItemIds);
        coveredItemIds.addAll(deletedItemIds);
        if (!coveredItemIds.equals(activeItemIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_MISSING_ACTIVE_ITEM);
        }
    }

    private Set<Long> normalizeDeletedItemIds(List<Long> deletedItemIds) {
        if (deletedItemIds == null || deletedItemIds.isEmpty()) {
            return Collections.emptySet();
        }

        return new HashSet<>(deletedItemIds);
    }

    private List<PreparedManageItem> prepareItems(List<ManageMapItemRequest> items) {
        List<PreparedManageItem> preparedItems = new ArrayList<>();

        for (ManageMapItemRequest item : items) {
            validateBasicTimeRange(item.startTime(), item.endTime());

            YoutubeMetadata metadata = youtubeValidationService.validateYoutubeUrl(item.youtubeUrl());
            validateTimeRangeWithinDuration(item.startTime(), item.endTime(), metadata.durationSeconds());

            preparedItems.add(new PreparedManageItem(
                    item,
                    metadata,
                    serializeAnswers(item.answers()),
                    item.hint().trim(),
                    item.hintTime() == null ? DEFAULT_HINT_TIME : item.hintTime()
            ));
        }

        return preparedItems;
    }

    private void validateBasicTimeRange(Integer startTime, Integer endTime) {
        if (startTime == null || endTime == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_TIME_RANGE);
        }

        if (startTime < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_NEGATIVE_START_TIME);
        }

        if (endTime <= startTime) {
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

        return jsonMapper.readValue(raw, new TypeReference<List<String>>() {
        });
    }

    private void recalculateMapMetadata(QuizMap quizMap, List<PreparedManageItem> preparedItems) {
        int numOfSong = preparedItems.size();
        int totalPlayTime = preparedItems.stream()
                .map(PreparedManageItem::request)
                .mapToInt(item -> item.endTime() - item.startTime())
                .sum();

        quizMap.updateMetadata(numOfSong, totalPlayTime);
    }

    private void applyPublicationChange(QuizMap quizMap, boolean requestedPublic) {
        if (!requestedPublic) {
            quizMap.markAsUnpublished(false);
            return;
        }

        mapItemJpaRepository.flush();
        publicationValidator.requirePublishable(quizMap.getId());
        quizMap.markAsPublished();
    }

    private MapDetailResponse toMapDetailResponse(QuizMap quizMap) {
        return MapDetailResponse.builder()
                .id(quizMap.getId())
                .ownerId(quizMap.getOwner().getId())
                .ownerNickname(quizMap.getOwner().getUsername())
                .title(quizMap.getTitle())
                .description(quizMap.getDescription())
                .category(quizMap.getCategory())
                .numOfSong(quizMap.getNumOfSong())
                .totalPlayTime(quizMap.getTotalPlayTime())
                .isPublic(Boolean.TRUE.equals(quizMap.getIsPublic()))
                .pendingPublic(Boolean.TRUE.equals(quizMap.getPendingPublic()))
                .playCount(quizMap.getPlayCount())
                .createdAt(quizMap.getCreatedAt())
                .updatedAt(quizMap.getUpdatedAt())
                .build();
    }

    private MapItemResponse toMapItemResponse(MapItem mapItem) {
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

    private record PreparedManageItem(
            ManageMapItemRequest request,
            YoutubeMetadata metadata,
            String answersJson,
            String hint,
            int hintTime
    ) {
    }
}