package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsItemRequest;
import io.github.ascrew.monomatbe.domain.map.dto.CreateMapWithItemsRequest;
import io.github.ascrew.monomatbe.domain.map.entity.MapCategory;
import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import io.github.ascrew.monomatbe.domain.map.repository.QuizMapJpaRepository;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MapCreateWithItemsTransactionIntegrationTest {

    @Autowired
    private MapManageTransactionService mapManageTransactionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private QuizMapJpaRepository quizMapJpaRepository;

    @Autowired
    private MapItemJpaRepository mapItemJpaRepository;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .username("bulk-owner-" + UUID.randomUUID())
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());
    }

    @Test
    void createMapWithItemsInTransaction_success_persistsMapAndItems() {
        CreateMapWithItemsItemRequest firstItemRequest = new CreateMapWithItemsItemRequest(
                1,
                "https://www.youtube.com/watch?v=video1",
                30,
                60,
                List.of("ditto"),
                "ㄷㅌ",
                15
        );
        CreateMapWithItemsItemRequest secondItemRequest = new CreateMapWithItemsItemRequest(
                2,
                "https://www.youtube.com/watch?v=video2",
                0,
                30,
                List.of("omg"),
                "ㅇㅇㅈ",
                15
        );

        CreateMapWithItemsRequest request = new CreateMapWithItemsRequest(
                "J-POP 퀴즈",
                "J-POP 중심 퀴즈 맵",
                MapCategory.JPOP,
                false,
                List.of(firstItemRequest, secondItemRequest)
        );

        List<PreparedManageItem> preparedItems = List.of(
                preparedItem(
                        firstItemRequest,
                        new YoutubeMetadata(
                                "video1",
                                "YouTube title 1",
                                "YouTube author 1",
                                "https://thumbnail/1",
                                null
                        ),
                        "[\"ditto\"]"
                ),
                preparedItem(
                        secondItemRequest,
                        new YoutubeMetadata(
                                "video2",
                                "YouTube title 2",
                                "YouTube author 2",
                                "https://thumbnail/2",
                                null
                        ),
                        "[\"omg\"]"
                )
        );

        var response = mapManageTransactionService.createMapWithItemsInTransaction(
                owner,
                request,
                preparedItems
        );

        assertThat(response.map().id()).isNotNull();
        assertThat(response.map().ownerId()).isEqualTo(owner.getId());
        assertThat(response.map().ownerNickname()).isEqualTo(owner.getUsername());
        assertThat(response.map().title()).isEqualTo("J-POP 퀴즈");
        assertThat(response.map().description()).isEqualTo("J-POP 중심 퀴즈 맵");
        assertThat(response.map().category()).isEqualTo(MapCategory.JPOP);
        assertThat(response.map().numOfSong()).isEqualTo(2);
        assertThat(response.map().totalPlayTime()).isEqualTo(60);
        assertThat(response.map().isPublic()).isFalse();
        assertThat(response.map().pendingPublic()).isFalse();
        assertThat(response.map().playCount()).isZero();

        assertThat(response.items()).hasSize(2);
        assertThat(response.items())
                .extracting(item -> item.orderNum())
                .containsExactly(1, 2);

        List<QuizMap> maps = findMapsOwnedBy(owner);
        assertThat(maps).hasSize(1);

        QuizMap savedMap = maps.get(0);
        assertThat(savedMap.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(savedMap.getTitle()).isEqualTo("J-POP 퀴즈");
        assertThat(savedMap.getDescription()).isEqualTo("J-POP 중심 퀴즈 맵");
        assertThat(savedMap.getCategory()).isEqualTo(MapCategory.JPOP);
        assertThat(savedMap.getNumOfSong()).isEqualTo(2);
        assertThat(savedMap.getTotalPlayTime()).isEqualTo(60);
        assertThat(savedMap.getIsPublic()).isFalse();
        assertThat(savedMap.getPendingPublic()).isFalse();
        assertThat(savedMap.getPlayCount()).isZero();

        List<MapItem> items = findItemsByMaps(maps);
        assertThat(items).hasSize(2);
        assertThat(items)
                .extracting(MapItem::getOrderNum)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(items)
                .extracting(MapItem::getVideoId)
                .containsExactlyInAnyOrder("video1", "video2");
    }

    @Test
    void createMapWithItemsInTransaction_whenItemFlushFails_rollsBackMapAndItems() {
        CreateMapWithItemsItemRequest firstItemRequest = new CreateMapWithItemsItemRequest(
                1,
                "https://www.youtube.com/watch?v=video1",
                30,
                60,
                List.of("ditto"),
                "ㄷㅌ",
                15
        );

        /*
         * Bean Validation은 Controller 계층에서 수행된다.
         * 이 테스트는 TransactionService rollback 검증이 목적이므로
         * 직접 DTO를 생성해 DB NOT NULL 제약 위반을 유도한다.
         *
         * orderNum = null
         * → map_item.order_num NOT NULL 위반
         * → flush/save 시 DataIntegrityViolationException
         * → service에서 ResponseStatusException(409) 변환
         * → @Transactional rollback
         */
        CreateMapWithItemsItemRequest invalidSecondItemRequest = new CreateMapWithItemsItemRequest(
                null,
                "https://www.youtube.com/watch?v=video2",
                0,
                30,
                List.of("omg"),
                "ㅇㅇㅈ",
                15
        );

        CreateMapWithItemsRequest request = new CreateMapWithItemsRequest(
                "Rollback 대상 맵",
                "두 번째 아이템 저장 실패 시 map까지 rollback되어야 함",
                MapCategory.JPOP,
                false,
                List.of(firstItemRequest, invalidSecondItemRequest)
        );

        List<PreparedManageItem> preparedItems = List.of(
                preparedItem(
                        firstItemRequest,
                        new YoutubeMetadata(
                                "video1",
                                "YouTube title 1",
                                "YouTube author 1",
                                "https://thumbnail/1",
                                null
                        ),
                        "[\"ditto\"]"
                ),
                preparedItem(
                        invalidSecondItemRequest,
                        new YoutubeMetadata(
                                "video2",
                                "YouTube title 2",
                                "YouTube author 2",
                                "https://thumbnail/2",
                                null
                        ),
                        "[\"omg\"]"
                )
        );

        assertThatThrownBy(() -> mapManageTransactionService.createMapWithItemsInTransaction(
                owner,
                request,
                preparedItems
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        List<QuizMap> maps = findMapsOwnedBy(owner);
        List<MapItem> items = findItemsByMaps(maps);

        assertThat(maps).isEmpty();
        assertThat(items).isEmpty();
    }

    private List<QuizMap> findMapsOwnedBy(User owner) {
        return quizMapJpaRepository.findAll()
                .stream()
                .filter(map -> map.getOwner().getId().equals(owner.getId()))
                .toList();
    }

    private List<MapItem> findItemsByMaps(List<QuizMap> maps) {
        Set<Long> mapIds = maps.stream()
                .map(QuizMap::getId)
                .collect(Collectors.toSet());

        if (mapIds.isEmpty()) {
            return List.of();
        }

        return mapItemJpaRepository.findAll()
                .stream()
                .filter(item -> mapIds.contains(item.getMap().getId()))
                .toList();
    }

    private PreparedManageItem preparedItem(
            CreateMapWithItemsItemRequest item,
            YoutubeMetadata metadata,
            String answersJson
    ) {
        return new PreparedManageItem(
                toPrepareSource(item),
                metadata,
                answersJson,
                item.hint(),
                item.hintTime()
        );
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
}