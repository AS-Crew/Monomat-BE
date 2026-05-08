package io.github.ascrew.monomatbe.domain.map.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapCategoryTest {

    @Test
    void from_acceptsLowercaseAndHyphenVariants() {
        assertThat(MapCategory.from("kpop")).isEqualTo(MapCategory.KPOP);
        assertThat(MapCategory.from("jpop")).isEqualTo(MapCategory.JPOP);
        assertThat(MapCategory.from("pop")).isEqualTo(MapCategory.POP);
        assertThat(MapCategory.from("K-POP")).isEqualTo(MapCategory.KPOP);
    }

    @Test
    void from_rejectsUnsupportedCategory() {
        assertThatThrownBy(() -> MapCategory.from("rock"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 category");
    }
}
