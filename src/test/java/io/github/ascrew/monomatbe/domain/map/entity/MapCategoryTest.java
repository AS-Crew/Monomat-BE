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
    void from_acceptsOstAndAnimeVariants() {
        assertThat(MapCategory.from("ost")).isEqualTo(MapCategory.OST);
        assertThat(MapCategory.from("OST")).isEqualTo(MapCategory.OST);
        assertThat(MapCategory.from("anime")).isEqualTo(MapCategory.ANIME);
        assertThat(MapCategory.from("ANIME")).isEqualTo(MapCategory.ANIME);
        assertThat(MapCategory.from("애니")).isEqualTo(MapCategory.ANIME);
    }

    @Test
    void value_returnsDisplayValue() {
        assertThat(MapCategory.OST.value()).isEqualTo("OST");
        assertThat(MapCategory.ANIME.value()).isEqualTo("애니");
    }

    @Test
    void from_rejectsUnsupportedCategory() {
        assertThatThrownBy(() -> MapCategory.from("rock"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 category");
    }
}
