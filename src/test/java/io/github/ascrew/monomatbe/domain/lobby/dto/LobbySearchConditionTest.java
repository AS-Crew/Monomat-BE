package io.github.ascrew.monomatbe.domain.lobby.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LobbySearchCondition의 요청 파라미터 정규화/검증 정책을 검증한다.
 */
class LobbySearchConditionTest {

    @Test
    @DisplayName("page와 size가 없으면 기본 페이징 값을 사용한다")
    void of_usesDefaultPagingWhenPageAndSizeAreNull() {
        // when
        LobbySearchCondition condition = LobbySearchCondition.of(
                null,
                null,
                null,
                null,
                null
        );

        // then
        assertThat(condition.pageRequest().page()).isEqualTo(LobbyPageRequest.DEFAULT_PAGE);
        assertThat(condition.pageRequest().size()).isEqualTo(LobbyPageRequest.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("page와 size가 있으면 요청 값을 사용한다")
    void of_usesRequestedPagingValues() {
        // when
        LobbySearchCondition condition = LobbySearchCondition.of(
                null,
                null,
                "latest",
                2,
                50
        );

        // then
        assertThat(condition.pageRequest().page()).isEqualTo(2);
        assertThat(condition.pageRequest().size()).isEqualTo(50);
    }

    @Test
    @DisplayName("page가 음수이면 400 예외를 던진다")
    void of_throwsExceptionWhenPageIsNegative() {
        assertThatThrownBy(() -> LobbySearchCondition.of(
                null,
                null,
                "latest",
                -1,
                20
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    @DisplayName("size가 1보다 작으면 400 예외를 던진다")
    void of_throwsExceptionWhenSizeIsLessThanOne() {
        assertThatThrownBy(() -> LobbySearchCondition.of(
                null,
                null,
                "latest",
                0,
                0
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    @DisplayName("size가 최대값을 초과하면 400 예외를 던진다")
    void of_throwsExceptionWhenSizeExceedsMaxSize() {
        assertThatThrownBy(() -> LobbySearchCondition.of(
                null,
                null,
                "latest",
                0,
                LobbyPageRequest.MAX_SIZE + 1
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }
}