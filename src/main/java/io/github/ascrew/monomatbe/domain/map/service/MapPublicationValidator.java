package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.MapPublicationPolicy;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.youtube.YoutubeVideoId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class MapPublicationValidator {

    private static final String ERROR_NO_ITEMS =
            "공개를 위해서는 최소 " + MapPublicationPolicy.MIN_ITEMS + "개의 문제가 필요합니다.";
    private static final String ERROR_INVALID_START_TIME = "%d번 문제의 시작 시간이 0 미만입니다.";
    private static final String ERROR_INVALID_TIME_RANGE = "%d번 문제의 재생 구간이 올바르지 않습니다.";
    private static final String ERROR_SEGMENT_TOO_SHORT =
            "%d번 문제의 재생 구간 길이는 최소 " + MapPublicationPolicy.MIN_SEGMENT_SECONDS + "초 이상이어야 합니다.";
    private static final String ERROR_SEGMENT_TOO_LONG =
            "%d번 문제의 재생 구간 길이는 최대 " + MapPublicationPolicy.MAX_SEGMENT_SECONDS + "초를 초과할 수 없습니다.";
    private static final String ERROR_BLANK_YOUTUBE_URL = "%d번 문제의 YouTube 주소가 비어 있습니다.";
    private static final String ERROR_INVALID_VIDEO_ID = "%d번 문제의 YouTube 영상 ID가 올바르지 않습니다.";
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
        if (items.size() < MapPublicationPolicy.MIN_ITEMS) {
            return ERROR_NO_ITEMS;
        }

        for (MapItem item : items) {
            Integer startTime = item.getStartTime();
            Integer endTime = item.getEndTime();
            String youtubeUrl = item.getYoutubeUrl();
            String videoId = item.getVideoId();
            String answer = item.getAnswer();

            if (startTime == null || startTime < 0) {
                return String.format(ERROR_INVALID_START_TIME, item.getOrderNum());
            }
            if (endTime == null || endTime <= startTime) {
                return String.format(ERROR_INVALID_TIME_RANGE, item.getOrderNum());
            }

            int duration = endTime - startTime;
            if (duration < MapPublicationPolicy.MIN_SEGMENT_SECONDS) {
                return String.format(ERROR_SEGMENT_TOO_SHORT, item.getOrderNum());
            }
            if (duration > MapPublicationPolicy.MAX_SEGMENT_SECONDS) {
                return String.format(ERROR_SEGMENT_TOO_LONG, item.getOrderNum());
            }

            if (youtubeUrl == null || youtubeUrl.isBlank()) {
                return String.format(ERROR_BLANK_YOUTUBE_URL, item.getOrderNum());
            }
            if (!YoutubeVideoId.isValid(videoId)) {
                return String.format(ERROR_INVALID_VIDEO_ID, item.getOrderNum());
            }
            if (answer == null || answer.isBlank()) {
                return String.format(ERROR_BLANK_ANSWER, item.getOrderNum());
            }
        }

        return null;
    }
}
