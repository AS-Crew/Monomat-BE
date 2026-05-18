package io.github.ascrew.monomatbe.domain.map.service;

import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import io.github.ascrew.monomatbe.domain.map.repository.MapItemJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MapPublicationValidatorTest {

    @Mock
    private MapItemJpaRepository mapItemJpaRepository;

    private MapPublicationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MapPublicationValidator(mapItemJpaRepository);
    }

    @Test
    void requirePublishable_noItems_throwsConflict() {
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> validator.requirePublishable(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("최소 1개의 문제가 필요합니다");
    }

    @Test
    void requirePublishable_startTimeNegative_throwsConflict() {
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item(1, -1, 30, "정답")));

        assertThatThrownBy(() -> validator.requirePublishable(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("1번 문제");
    }

    @Test
    void requirePublishable_endTimeNotGreaterThanStartTime_throwsConflict() {
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item(2, 30, 30, "정답")));

        assertThatThrownBy(() -> validator.requirePublishable(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("2번 문제");
    }

    @Test
    void requirePublishable_blankAnswer_throwsConflict() {
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item(3, 0, 30, "   ")));

        assertThatThrownBy(() -> validator.requirePublishable(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("3번 문제");
    }

    @Test
    void requirePublishable_allValid_doesNotThrow() {
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item(1, 0, 30, "정답1"), item(2, 10, 40, "정답2")));

        validator.requirePublishable(1L);
    }

    @Test
    void isPublishable_returnsTrueWhenValid_falseOtherwise() {
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(1L))
                .thenReturn(List.of(item(1, 0, 30, "정답")));
        when(mapItemJpaRepository.findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(2L))
                .thenReturn(List.of());

        assertThat(validator.isPublishable(1L)).isTrue();
        assertThat(validator.isPublishable(2L)).isFalse();
    }

    private MapItem item(int orderNum, int startTime, int endTime, String answer) {
        return MapItem.builder()
                .orderNum(orderNum)
                .startTime(startTime)
                .endTime(endTime)
                .answer(answer)
                .build();
    }
}
