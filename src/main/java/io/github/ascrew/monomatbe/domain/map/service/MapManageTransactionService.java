package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsRequest;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsResponse;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsItemRequest;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private static final String ERROR_INVALID_PREPARED_ITEMS =
            "맵 관리 일괄 저장 준비 데이터가 요청 데이터와 일치하지 않습니다.";

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
        validatePreparedItems(request.items(), preparedItems);

        /*
         * 벌크 orderNum 임시 변경 전에 반드시 소유권 검증과 PESSIMISTIC_WRITE 락을 먼저 획득한다.
         * 이 조회를 제거하면 권한 없는 요청이 setTemporaryOrderNums(mapId)를 먼저 실행할 수 있어
         * 타인 맵의 orderNum을 임시 음수로 변경하는 보안/동시성 문제가 생길 수 있다.
         */
        QuizMap quizMap = getOwnedMapForWriteOrThrow(mapId, principal.userId());

        List<MapItem> activeItems = mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId);
        validateItemIdentity(activeItems, request);

        try {
            /*
             * setTemporaryOrderNums()는 clearAutomatically=true 벌크 업데이트이므로
             * 영속성 컨텍스트가 초기화된다. 이후 변경에 사용할 QuizMap/MapItem은 반드시 재조회한다.
             */
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

            List<MapItem> latestItems = new ArrayList<>();

            for (PreparedManageItem preparedItem : preparedItems) {
                MapItemPrepareSource source = preparedItem.source();

                if (source.id() == null) {
                    MapItem saved = mapItemJpaRepository.save(MapItem.builder()
                            .map(quizMap)
                            .orderNum(source.orderNum())
                            .youtubeUrl(source.youtubeUrl().trim())
                            .videoId(preparedItem.metadata().videoId())
                            .startTime(source.startTime())
                            .endTime(source.endTime())
                            .title(preparedItem.metadata().title())
                            .artist(preparedItem.metadata().artist())
                            .thumbnailUrl(preparedItem.metadata().thumbnailUrl())
                            .answers(preparedItem.answersJson())
                            .hint(preparedItem.hint())
                            .hintTime(preparedItem.hintTime())
                            .build());

                    latestItems.add(saved);
                    continue;
                }

                MapItem mapItem = activeItemById.get(source.id());
                if (mapItem == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ITEM_ID);
                }

                mapItem.update(
                        source.orderNum(),
                        source.youtubeUrl().trim(),
                        preparedItem.metadata().videoId(),
                        source.startTime(),
                        source.endTime(),
                        preparedItem.metadata().title(),
                        preparedItem.metadata().artist(),
                        preparedItem.metadata().thumbnailUrl(),
                        preparedItem.answersJson(),
                        preparedItem.hint(),
                        preparedItem.hintTime()
                );

                latestItems.add(mapItem);
            }

            recalculateMapMetadata(quizMap, preparedItems);
            applyPublicationChange(quizMap, request.isPublic());

            mapItemJpaRepository.flush();

            latestItems.sort(Comparator.comparing(MapItem::getOrderNum));

            registerCacheEvictionAfterCommit(mapId);

            return ManageMapResponse.builder()
                    .map(toMapDetailResponse(quizMap))
                    .items(latestItems.stream().map(this::toMapItemResponse).toList())
                    .build();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ACTIVE_ORDER, e);
        }
    }

    @Transactional
    public CreateMapWithItemsResponse createMapWithItemsInTransaction(
            User owner,
            CreateMapWithItemsRequest request,
            List<PreparedManageItem> preparedItems
    ) {
        validateCreatePreparedItems(request, preparedItems);

        QuizMap quizMap = quizMapJpaRepository.save(QuizMap.builder()
                .owner(owner)
                .title(request.title())
                .description(request.description())
                .category(request.category())
                .numOfSong(0)
                .totalPlayTime(0)
                .playCount(0L)
                .isPublic(false)
                .pendingPublic(false)
                .isDeleted(false)
                .build());

        List<MapItem> savedItems = new ArrayList<>();

        try {
            for (PreparedManageItem preparedItem : preparedItems) {
                MapItemPrepareSource source = preparedItem.source();

                MapItem savedItem = mapItemJpaRepository.save(MapItem.builder()
                        .map(quizMap)
                        .orderNum(source.orderNum())
                        .youtubeUrl(source.youtubeUrl().trim())
                        .videoId(preparedItem.metadata().videoId())
                        .startTime(source.startTime())
                        .endTime(source.endTime())
                        .title(preparedItem.metadata().title())
                        .artist(preparedItem.metadata().artist())
                        .thumbnailUrl(preparedItem.metadata().thumbnailUrl())
                        .answers(preparedItem.answersJson())
                        .hint(preparedItem.hint())
                        .hintTime(preparedItem.hintTime())
                        .isDeleted(false)
                        .build());

                savedItems.add(savedItem);
            }

            recalculateMapMetadata(quizMap, preparedItems);
            applyPublicationChange(quizMap, request.isPublic());

            mapItemJpaRepository.flush();

            savedItems.sort(Comparator.comparing(MapItem::getOrderNum));

            registerCacheEvictionAfterCommit(quizMap.getId());

            return CreateMapWithItemsResponse.builder()
                    .map(toMapDetailResponse(quizMap))
                    .items(savedItems.stream().map(this::toMapItemResponse).toList())
                    .build();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ACTIVE_ORDER, e);
        }
    }

    private void validateCreatePreparedItems(
            CreateMapWithItemsRequest request,
            List<PreparedManageItem> preparedItems
    ) {
        if (preparedItems == null || request.items().size() != preparedItems.size()) {
            throw new IllegalStateException(ERROR_INVALID_PREPARED_ITEMS);
        }

        for (int i = 0; i < request.items().size(); i++) {
            if (!toPrepareSource(request.items().get(i)).equals(preparedItems.get(i).source())) {
                throw new IllegalStateException(ERROR_INVALID_PREPARED_ITEMS);
            }
        }
    }

    private MapItemPrepareSource toPrepareSource(CreateMapWithItemsItemRequest item) {
        return new MapItemPrepareSource(
                null,
                item.orderNum(),
                item.youtubeUrl(),
                item.startTime(),
                item.endTime(),
                item.answers(),
                item.hint(),
                item.hintTime()
        );
    }

    private void validatePreparedItems(
            List<ManageMapItemRequest> requestItems,
            List<PreparedManageItem> preparedItems
    ) {
        if (preparedItems == null || requestItems.size() != preparedItems.size()) {
            throw new IllegalStateException(ERROR_INVALID_PREPARED_ITEMS);
        }

        for (int i = 0; i < requestItems.size(); i++) {
            if (!toPrepareSource(requestItems.get(i)).equals(preparedItems.get(i).source())) {
                throw new IllegalStateException(ERROR_INVALID_PREPARED_ITEMS);
            }
        }
    }

    private MapItemPrepareSource toPrepareSource(ManageMapItemRequest item) {
        return new MapItemPrepareSource(
                item.id(),
                item.orderNum(),
                item.youtubeUrl(),
                item.startTime(),
                item.endTime(),
                item.answers(),
                item.hint(),
                item.hintTime()
        );
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
                .map(PreparedManageItem::source)
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

    private void registerCacheEvictionAfterCommit(Long mapId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            mapCacheEvictor.evictPublicMapCaches(mapId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mapCacheEvictor.evictPublicMapCaches(mapId);
            }
        });
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