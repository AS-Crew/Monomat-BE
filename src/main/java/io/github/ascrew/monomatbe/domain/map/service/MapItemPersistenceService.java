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

import java.util.List;
import java.util.Objects;

@Service
public class MapItemPersistenceService {

    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 문제를 관리할 수 있습니다.";
    private static final String ERROR_MAP_ITEM_NOT_FOUND = "문제를 찾을 수 없습니다.";
    private static final String ERROR_DUPLICATE_ORDER = "이미 사용 중인 문제 순서입니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;

    public MapItemPersistenceService(
            QuizMapJpaRepository quizMapJpaRepository,
            MapItemJpaRepository mapItemJpaRepository
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
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
    }

    private QuizMap getOwnedMapOrThrow(Long mapId, Long ownerId) {
        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));
        if (!Objects.equals(quizMap.getOwner().getId(), ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_MAP_FORBIDDEN);
        }
        return quizMap;
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
