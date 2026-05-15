package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.MapItemResponse;
import io.github.ascrew.monomatbe.domain.map.dto.UpdateMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.map.support.HintTextGenerator;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class MapItemService {

    private static final int DEFAULT_HINT_TIME = 15;

    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵 문제를 관리할 수 있습니다.";
    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 문제를 관리할 수 있습니다.";
    private static final String ERROR_MAP_ITEM_NOT_FOUND = "문제를 찾을 수 없습니다.";
    private static final String ERROR_INVALID_TIME_RANGE = "재생 구간은 시작 시간보다 종료 시간이 커야 합니다.";
    private static final String ERROR_DUPLICATE_ORDER = "이미 사용 중인 문제 순서입니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;
    private final MapItemJpaRepository mapItemJpaRepository;
    private final YoutubeValidationService youtubeValidationService;
    private final MapCacheEvictor mapCacheEvictor;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public MapItemService(
            QuizMapJpaRepository quizMapJpaRepository,
            MapItemJpaRepository mapItemJpaRepository,
            YoutubeValidationService youtubeValidationService,
            MapCacheEvictor mapCacheEvictor,
            @Qualifier("pubSubJsonMapper") JsonMapper jsonMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.mapItemJpaRepository = mapItemJpaRepository;
        this.youtubeValidationService = youtubeValidationService;
        this.mapCacheEvictor = mapCacheEvictor;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<MapItemResponse> getMapItems(Long mapId, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);
        QuizMap quizMap = getOwnedMapOrThrow(mapId, principal.userId());

        return mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(quizMap.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public MapItemResponse createMapItem(Long mapId, CreateMapItemRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);
        validateTimeRange(request.startTime(), request.endTime());

        // 외부 oEmbed 호출은 트랜잭션/DB 커넥션 점유 밖에서 수행한다.
        YoutubeMetadata metadata = youtubeValidationService.validateYoutubeUrl(request.youtubeUrl());
        String normalizedAnswer = request.answer().trim();
        String altAnswersJson = serializeAltAnswers(request.altAnswers());
        int hintTime = request.hintTime() == null ? DEFAULT_HINT_TIME : request.hintTime();
        String hint = buildHint(request.hint(), normalizedAnswer);

        // 서비스 레벨 pre-check는 단일 요청 fast-fail 용도이고,
        // 실제 동시성 보장은 DB 레벨 (map_id, active_order_num) UNIQUE 제약이 담당한다.
        MapItem created;
        try {
            created = transactionTemplate.execute(status -> {
                QuizMap quizMap = getOwnedMapOrThrow(mapId, principal.userId());
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
            });
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER, e);
        }

        mapCacheEvictor.evictPublicMapCaches(mapId);
        return toResponse(created);
    }

    public MapItemResponse updateMapItem(Long mapId, Long itemId, UpdateMapItemRequest request, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);
        validateTimeRange(request.startTime(), request.endTime());

        // 외부 oEmbed 호출은 트랜잭션/DB 커넥션 점유 밖에서 수행한다.
        YoutubeMetadata metadata = youtubeValidationService.validateYoutubeUrl(request.youtubeUrl());
        String normalizedAnswer = request.answer().trim();
        String altAnswersJson = serializeAltAnswers(request.altAnswers());
        int hintTime = request.hintTime() == null ? DEFAULT_HINT_TIME : request.hintTime();
        String hint = buildHint(request.hint(), normalizedAnswer);

        MapItem updated;
        try {
            updated = transactionTemplate.execute(status -> {
                QuizMap quizMap = getOwnedMapOrThrow(mapId, principal.userId());

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
            });
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_ORDER, e);
        }

        mapCacheEvictor.evictPublicMapCaches(mapId);
        return toResponse(updated);
    }

    @Transactional
    public void deleteMapItem(Long mapId, Long itemId, CustomPrincipal principal) {
        validateRegisteredPrincipal(principal);
        QuizMap quizMap = getOwnedMapOrThrow(mapId, principal.userId());

        MapItem mapItem = mapItemJpaRepository.findByIdAndMapIdAndIsDeletedFalse(itemId, mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_ITEM_NOT_FOUND));

        mapItem.softDelete();
        recalculateMapMetadata(quizMap);
        mapCacheEvictor.evictPublicMapCaches(quizMap.getId());
    }

    private void recalculateMapMetadata(QuizMap quizMap) {
        int numOfSong = (int) mapItemJpaRepository.countByMapIdAndIsDeletedFalse(quizMap.getId());
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

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }
        if (principal.userType() != UserType.REGISTERED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }
    }

    private void validateTimeRange(Integer startTime, Integer endTime) {
        if (startTime == null || endTime == null || endTime <= startTime) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_TIME_RANGE);
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

    private String buildHint(String hint, String answer) {
        if (hint != null && !hint.isBlank()) {
            return hint.trim();
        }
        return HintTextGenerator.toInitialConsonants(answer);
    }

    private String serializeAltAnswers(List<String> altAnswers) {
        List<String> normalized = altAnswers == null ? Collections.emptyList() : altAnswers.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        return jsonMapper.writeValueAsString(normalized);
    }

    private List<String> deserializeAltAnswers(String raw) {
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
                .answer(mapItem.getAnswer())
                .altAnswers(deserializeAltAnswers(mapItem.getAltAnswers()))
                .hint(mapItem.getHint())
                .hintTime(mapItem.getHintTime())
                .createdAt(mapItem.getCreatedAt())
                .updatedAt(mapItem.getUpdatedAt())
                .build();
    }
}
