package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapDetailResponse;
import io.github.ascrew.monomatbe.domain.map.dto.MapItemResponse;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MapManageTransactionService {

    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 수정할 수 있습니다.";
    private static final String ERROR_ITEM_DELETE_CONFLICT = "수정할 문제와 삭제할 문제가 중복되었습니다.";
    private static final String ERROR_MISSING_ACTIVE_ITEM = "기존 활성 문제는 수정 목록 또는 삭제 목록에 모두 포함되어야 합니다.";
    private static final String ERROR_INVALID_ITEM_ID = "현재 맵에 속하지 않는 문제 ID가 포함되어 있습니다.";
    private static final String ERROR_DUPLICATE_ACTIVE_ORDER = "이미 사용 중인 문제 순서입니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final MapPublicationValidator publicationValidator;
    private final MapCacheEvictor mapCacheEvictor;
    private final JsonMapper jsonMapper;

    public MapManageTransactionService(
            QuizMapJpaRepository quizMapJpaRepository,
            MapItemJpaRepository mapItemJpaRepository,
            MapPublicationValidator publicationValidator,
            MapCacheEvictor mapCacheEvictor,
            @Qualifier("pubSubJsonMapper") JsonMapper jsonMapper
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.publicationValidator = publicationValidator;
        this.mapCacheEvictor = mapCacheEvictor;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public ManageMapResponse updateManagedMapInTransaction(
            Long mapId,
            ManageMapRequest request,
            CustomPrincipal principal,
            List<PreparedManageItem> preparedItems
    ) {
        QuizMap quizMap = getOwnedMapForWriteOrThrow(mapId, principal.userId());

        List<MapItem> activeItems = mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId);
        validateItemIdentity(activeItems, request);

        try {
            // 기존 활성 문제의 orderNum을 모두 음수로 밀어 최종 순서 적용 중 UNIQUE 충돌을 방지한다.
            // 이 쿼리는 clearAutomatically=true 이므로 이후 엔티티는 반드시 재조회한다.
            mapItemJpaRepository.setTemporaryOrderNums(mapId);

            quizMap = getOwnedMapForWriteOrThrow(mapId, principal.userId());
            java.util.Map<Long, MapItem> activeItemById = mapItemJpaRepository
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

    private QuizMap getOwnedMapForWriteOrThrow(Long mapId, Long ownerId) {
        return quizMapJpaRepository.findOwnedByIdAndIsDeletedFalseForUpdate(mapId, ownerId)
                .orElseThrow(() -> {
                    if (quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId).isEmpty()) {
                        return new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND);
                    }
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_MAP_FORBIDDEN);
                });
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

    private List<String> deserializeAnswers(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }

        return jsonMapper.readValue(raw, new TypeReference<List<String>>() {
        });
    }
}