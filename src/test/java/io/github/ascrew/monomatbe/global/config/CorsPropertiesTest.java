package io.github.ascrew.monomatbe.global.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    private CorsProperties withOrigins(String... origins) {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of(origins));
        return properties;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://monomat.games",
            "https://www.monomat.games",
            "http://localhost:5173",
            "http://localhost:3000"
    })
    void validateAllowedOrigins_passesForExactOrigins(String origin) {
        CorsProperties properties = withOrigins(origin);

        assertThatCode(properties::validateAllowedOrigins).doesNotThrowAnyException();
    }

    @Test
    void validateAllowedOrigins_normalizesWhitespaceAndBlankElements() {
        CorsProperties properties = withOrigins("  https://monomat.games  ", "", "   ");

        properties.validateAllowedOrigins();

        assertThat(properties.getAllowedOrigins()).containsExactly("https://monomat.games");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*",                          // 전체 허용
            "https://*.monomat.games",    // 서브도메인 와일드카드
            "monomat.games",              // scheme 없음
            "https://monomat.games/",     // 후행 슬래시(path)
            "https://monomat.games/app",  // path 포함
            "ftp://monomat.games",        // 허용되지 않는 scheme
            "https://user@monomat.games"  // userinfo 포함
    })
    void validateAllowedOrigins_throwsForInvalidOrigins(String origin) {
        CorsProperties properties = withOrigins(origin);

        assertThatThrownBy(properties::validateAllowedOrigins)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validateAllowedOrigins_throwsWhenEmptyAfterNormalization() {
        CorsProperties properties = withOrigins("", "   ");

        assertThatThrownBy(properties::validateAllowedOrigins)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be empty");
    }
}
