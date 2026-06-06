package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.map.MapItemPolicy;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapRequest;
import io.github.ascrew.monomatbe.domain.map.dto.ManageMapResponse;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.map.support.AnswerNormalizer;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.domain.youtube.service.YoutubeValidationService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class MapManageService {

    private static final int DEFAULT_HINT_TIME = 15;

    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵을 관리할 수 있습니다.";
    private static final String ERROR_MAP_NOT_FOUND = "맵을 찾을 수 없습니다.";
    private static final String ERROR_MAP_FORBIDDEN = "본인 소유의 맵만 수정할 수 있습니다.";
    private static final String ERROR_INVALID_TIME_RANGE = "재생 구간은 시작 시간보다 종료 시간이 커야 합니다.";
    private static final String ERROR_NEGATIVE_START_TIME = "재생 시작 시간은 0초 이상이어야 합니다.";
    private static final String ERROR_INVALID_VIDEO_DURATION = "YouTube 영상 길이 정보가 올바르지 않습니다.";
    private static final String ERROR_START_TIME_EXCEEDS_DURATION = "재생 시작 시간은 YouTube 영상 길이보다 작아야 합니다.";
    private static final String ERROR_END_TIME_EXCEEDS_DURATION = "재생 종료 시간은 YouTube 영상 길이를 초과할 수 없습니다.";
    private static final String ERROR_DUPLICATE_ORDER = "중복된 문제 순서가 있습니다.";
    private static final String ERROR_INVALID_ORDER_SEQUENCE = "문제 순서는 1부터 문제 수까지 중복 없이 지정해야 합니다.";
    private static final String ERROR_DUPLICATE_ITEM_ID = "중복된 문제 ID가 있습니다.";
    private static final String ERROR_NO_VALID_ANSWER = "정답은 최소 1개 이상이어야 합니다.";
    private static final String ERROR_MAP_ITEM_LIMIT_EXCEEDED =
            "한 맵에 등록할 수 있는 문제는 최대 " + MapItemPolicy.MAX_ITEMS_PER_MAP + "개입니다.";

    private final QuizMapJpaRepository quizMapJpaRepository;
    private final YoutubeValidationService youtubeValidationService;
    private final MapManageTransactionService mapManageTransactionService;
    private final JsonMapper jsonMapper;

    public MapManageService(
            QuizMapJpaRepository quizMapJpaRepository,
            YoutubeValidationService youtubeValidationService,
            MapManageTransactionService mapManageTransactionService,
            @Qualifier("pubSubJsonMapper") JsonMapper jsonMapper
    ) {
        this.quizMapJpaRepository = quizMapJpaRepository;
        this.youtubeValidationService = youtubeValidationService;
        this.mapManageTransactionService = mapManageTransactionService;
        this.jsonMapper = jsonMapper;
    }

    public ManageMapResponse updateManagedMap(
            Long mapId,
            ManageMapRequest request,
            CustomPrincipal principal
    ) {
        validateRegisteredPrincipal(principal);
        validateManageRequest(request);
        validateMapOwner(mapId, principal.userId());

        /*
         * 외부 oEmbed 호출은 DB 트랜잭션과 PESSIMISTIC_WRITE 락 밖에서 먼저 수행한다.
         * YouTube 응답 지연/장애가 DB row lock 점유로 전파되는 것을 막기 위함이다.
         */
        List<PreparedManageItem> preparedItems = prepareItems(request.items());

        return mapManageTransactionService.updateManagedMapInTransaction(
                mapId,
                request,
                principal,
                preparedItems
        );
    }

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }

        if (principal.userType() != UserType.REGISTERED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }
    }

    private void validateMapOwner(Long mapId, Long ownerId) {
        QuizMap quizMap = quizMapJpaRepository.findByIdAndIsDeletedFalse(mapId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_MAP_NOT_FOUND));

        if (!Objects.equals(quizMap.getOwner().getId(), ownerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_MAP_FORBIDDEN);
        }
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
}