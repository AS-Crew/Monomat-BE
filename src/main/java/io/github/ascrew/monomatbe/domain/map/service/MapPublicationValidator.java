package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class MapPublicationValidator {

    private static final String ERROR_NO_ITEMS = "공개를 위해서는 최소 1개의 문제가 필요합니다.";
    private static final String ERROR_INVALID_START_TIME = "%d번 문제의 시작 시간이 0 미만입니다.";
    private static final String ERROR_INVALID_TIME_RANGE = "%d번 문제의 재생 구간이 올바르지 않습니다.";
    private static final String ERROR_BLANK_ANSWER = "%d번 문제의 정답이 비어 있습니다.";

    private final MapItemJpaRepository mapItemJpaRepository;

    public MapPublicationValidator(MapItemJpaRepository mapItemJpaRepository) {
        this.mapItemJpaRepository = mapItemJpaRepository;
    }

    @Transactional(readOnly = true)
    public boolean isPublishable(Long mapId) {
        return findFailureMessage(mapId) == null;
    }

    @Transactional(readOnly = true)
    public void requirePublishable(Long mapId) {
        String failure = findFailureMessage(mapId);
        if (failure != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure);
        }
    }

    private String findFailureMessage(Long mapId) {
        List<MapItem> items = mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(mapId);
        if (items.isEmpty()) {
            return ERROR_NO_ITEMS;
        }

        for (MapItem item : items) {
            Integer startTime = item.getStartTime();
            Integer endTime = item.getEndTime();
            String answer = item.getAnswer();

            if (startTime == null || startTime < 0) {
                return String.format(ERROR_INVALID_START_TIME, item.getOrderNum());
            }
            if (endTime == null || endTime <= startTime) {
                return String.format(ERROR_INVALID_TIME_RANGE, item.getOrderNum());
            }
            if (answer == null || answer.isBlank()) {
                return String.format(ERROR_BLANK_ANSWER, item.getOrderNum());
            }
        }

        return null;
    }
}
