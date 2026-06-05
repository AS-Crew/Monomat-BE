package io.github.ascrew.monomatbe.global.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void mapPublicListKey_includesSchemaVersion() {
        String key = RedisKeys.mapPublicListKey("1", 0, 20);

        assertThat(key).isEqualTo("map:public:list:schema:2:v:1:p:0:s:20");
    }

    @Test
    void mapPublicListKeyWithCondition_includesSchemaVersion() {
        String key = RedisKeys.mapPublicListKey(
                "1",
                null,
                "KPOP",
                "NEWEST",
                0,
                20
        );

        assertThat(key).isEqualTo("map:public:list:schema:2:v:1:k::c:KPOP:sort:NEWEST:p:0:s:20");
    }

    @Test
    void mapPublicDetailKey_usesVersionedPrefix() {
        String key = RedisKeys.mapPublicDetailKey(300L);

        assertThat(key).isEqualTo("map:public:v2:300");
    }
}