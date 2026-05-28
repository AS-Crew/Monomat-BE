package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.dto.CreateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MapItemPersistenceService {

    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 문제를 관리할 수 있습니다.";
    private static final String ERROR_MAP_ITEM_NOT_FOUND = "문제를 찾을 수 없습니다.";
    private static final String ERROR_DUPLICATE_ORDER = "이미 사용 중인 문제 순서입니다.";
    private static final String ERROR_DUPLICATE_ITEM_ID = "중복된 문제 ID가 있습니다.";
    private static final String ERROR_MISSING_ITEMS = "모든 문제의 순서를 지정해야 합니다.";
    private static final String ERROR_INVALID_ITEM_ID = "유효하지 않은 문제 ID가 포함되어 있습니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final MapPublicationValidator publicationValidator;

    public MapItemPersistenceService(
            QuizMapJpaRepository quizMapJpaRepository,
            MapItemJpaRepository mapItemJpaRepository,
            MapPublicationValidator publicationValidator
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.publicationValidator = publicationValidator;
    }

    @Transactional(readOnly = true)
    public List<MapItem> findItemsForOwnedMap(Long mapId, Long ownerId) {
        QuizMap quizMap = getOwnedMapOrThrow(mapId, ownerId);
        return mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(quizMap.getId());
    }

    @Transactional
    public MapItem create(
            Long mapId,
            Long ownerId,
            CreateMapItemRequest request,
            YoutubeMetadata metadata,
            String normalizedAnswer,
            String altAnswersJson,
            String hint,
            int hintTime
    ) {
        QuizMap quizMap = getOwnedMapOrThrow(mapId, ownerId);
        validateOrderDuplicatedOnCreate(mapId, request.orderNum());

        MapItem saved = mapItemJpaRepository.save(MapItem.builder()
                .map(quizMap)
                .orderNum(request.orderNum())
                .youtubeUrl(request.youtubeUrl().trim())
                .videoId(metadata.videoId())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .title(metadata.title())
                .artist(metadata.artist())
                .thumbnailUrl(metadata.thumbnailUrl())
                .answer(normalizedAnswer)
                .altAnswers(altAnswersJson)
                .hint(hint)
                .hintTime(hintTime)
                .build());

        recalculateMapMetadata(quizMap);
        return saved;
    }

    @Transactional
    public MapItem update(
            Long mapId,
            Long itemId,
            Long ownerId,
            UpdateMapItemRequest request,
            YoutubeMetadata metadata,
            String normalizedAnswer,
            String altAnswersJson,
            String hint,
            int hintTime
    ) {
        QuizMap quizMap = getOwnedMapOrThrow(mapId, ownerId);

        MapItem mapItem = mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(itemId, mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_ITEM_NOT_FOUND));

        validateOrderDuplicatedOnUpdate(mapId, request.orderNum(), mapItem.getId());

        mapItem.update(
                request.orderNum(),
                request.youtubeUrl().trim(),
                metadata.videoId(),
                request.startTime(),
                request.endTime(),
                metadata.title(),
                metadata.artist(),
                metadata.thumbnailUrl(),
                normalizedAnswer,
                altAnswersJson,
                hint,
                hintTime
        );

        recalculateMapMetadata(quizMap);
        return mapItem;
    }

    @Transactional
    public void reorder(Long mapId, Long ownerId, List<Long> orderedItemIds) {
        getOwnedMapOrThrow(mapId, ownerId);

        List<MapItem> activeItems = mapItemJpaRepository
                .findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId);

        validateReorderRequest(activeItems, orderedItemIds);

        // Phase 1: -id(음수)로 일괄 변경 (최종값 1~N과 겹치지 않아 UNIQUE 충돌 없음)
        mapItemJpaRepository.setTemporaryOrderNums(mapId);

        // Phase 2: L1 캐시 클리어 후 재조회, 최종 orderNum 할당
        Map<Long, MapItem> itemById = mapItemJpaRepository
                .findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId)
                .stream().collect(Collectors.toMap(MapItem::getId, Function.identity()));

        for (int i = 0; i < orderedItemIds.size(); i++) {
            itemById.get(orderedItemIds.get(i)).reorder(i + 1);
        }
    }

    @Transactional
    public void delete(Long mapId, Long itemId, Long ownerId) {
        QuizMap quizMap = getOwnedMapOrThrow(mapId, ownerId);

        MapItem mapItem = mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(itemId, mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_ITEM_NOT_FOUND));

        mapItem.softDelete();
        recalculateMapMetadata(quizMap);
    }

    private void recalculateMapMetadata(QuizMap quizMap) {
        int numOfSong = Math.toIntExact(mapItemJpaRepository.countByMapIdAndIsDeletedFalse(quizMap.getId()));
        Long sum = mapItemJpaRepository.sumPlayTimeByMapId(quizMap.getId());
        int totalPlayTime = sum == null ? 0 : Math.toIntExact(sum);
        quizMap.updateMetadata(numOfSong, totalPlayTime);

        applyPublicationAutoFlip(quizMap);
    }

    // 아이템 CRUD 후 공개 상태를 자동 조정한다.
    //   - isPublic 상태에서 검증 미달(아이템 0개·시간 구간·정답 비어있음 등): 자동 비공개. 의도(pendingPublic)는 보존해 재공개 가능.
    //   - 비공개 + pendingPublic=true + 검증 통과: 사용자가 원래 의도한 공개로 자동 전환.
    private void applyPublicationAutoFlip(QuizMap quizMap) {
        boolean isPublic = Boolean.TRUE.equals(quizMap.getIsPublic());
        boolean pendingPublic = Boolean.TRUE.equals(quizMap.getPendingPublic());

        if (isPublic) {
            if (!publicationValidator.isPublishable(quizMap.getId())) {
                quizMap.markAsUnpublished(true);
            }
            return;
        }

        if (pendingPublic && publicationValidator.isPublishable(quizMap.getId())) {
            quizMap.markAsPublished();
        }
    }

    private QuizMap getOwnedMapOrThrow(Long mapId, Long ownerId) {
        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));
        if (!Objects.equals(quizMap.getOwner().getId(), ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_MAP_FORBIDDEN);
        }
        return quizMap;
    }

    private void validateReorderRequest(List<MapItem> activeItems, List<Long> orderedItemIds) {
        Set<Long> deduplicated = new HashSet<>(orderedItemIds);
        if (deduplicated.size() != orderedItemIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_DUPLICATE_ITEM_ID);
        }
        if (orderedItemIds.size() != activeItems.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_MISSING_ITEMS);
        }
        Set<Long> activeIds = activeItems.stream().map(MapItem::getId).collect(Collectors.toSet());
        for (Long id : orderedItemIds) {
            if (!activeIds.contains(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_ITEM_ID);
            }
        }
    }

    private void validateOrderDuplicatedOnCreate(Long mapId, Integer orderNum) {
        if (mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalse(mapId, orderNum)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER);
        }
    }

    private void validateOrderDuplicatedOnUpdate(Long mapId, Integer orderNum, Long itemId) {
        if (mapItemJpaRepository.existsByMapIdAndOrderNumAndIsDeletedFalseAndIdNot(mapId, orderNum, itemId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER);
        }
    }
}
