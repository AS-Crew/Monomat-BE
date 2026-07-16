package io.github.ascrew.monomatbe.domain.youtube.service;

import io.github.ascrew.monomatbe.domain.youtube.client.YoutubeOEmbedClient;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YoutubeValidationServiceTest {

    private static final String TEST_VIDEO_ID = "abcde123456";
    private static final String TEST_YOUTUBE_URL = "https://www.youtube.com/watch?v=" + TEST_VIDEO_ID;
    private static final String MIXED_CASE_ID = "dQw4w9WgXcQ";
    private static final String OEMBED_RESPONSE =
            "{\"title\":\"t\",\"author_name\":\"a\",\"thumbnail_url\":\"th\"}";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private YoutubeOEmbedClient youtubeOEmbedClient;

    private YoutubeValidationService youtubeValidationService;
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        youtubeValidationService = new YoutubeValidationService(
                redisTemplate,
                jsonMapper,
                youtubeOEmbedClient,
                8,
                Duration.ofSeconds(10)
        );

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void validateYoutubeUrl_invalidFormat_badRequest() {
        // example.com host는 YouTube 도메인이 아니므로 oEmbed 호출 전에 거절되어야 한다.
        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl("https://example.com/watch?v=abc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_nonYoutubeDomainWithValidVideoId_badRequest() {
        // 11자 유효 videoId가 있더라도 host가 YouTube 도메인이 아니면 거절되어야 한다.
        assertThatThrownBy(() -> youtubeValidationService
                .validateYoutubeUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_negativeCacheHit_blocksWithoutRevalidate() {
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID))).thenReturn(true);

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl(TEST_YOUTUBE_URL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_oembedReturnsBlankTitle_badRequestAndDoesNotStoreFailureCache() {
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId(TEST_VIDEO_ID))
                .thenReturn("""
                        {
                          "author_name":"artist",
                          "thumbnail_url":"thumb"
                        }
                        """);

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl(TEST_YOUTUBE_URL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        /*
         * title 누락은 현재 요청에서는 사용할 수 없는 응답이지만,
         * YouTube가 401/403/404를 반환한 영구적 임베드 불가 상태는 아니다.
         *
         * 따라서 Negative Cache에 저장하지 않아야 한다.
         * 저장해버리면 일시적인 oEmbed 응답 이상이 30분 동안 영구 실패처럼 고정된다.
         */
        verify(valueOperations, never()).set(
                eq(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID)),
                anyString(),
                any()
        );
    }

    @Test
    void validateYoutubeUrl_oembedReturnsBlankArtist_badRequestAndDoesNotStoreFailureCache() {
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId(TEST_VIDEO_ID))
                .thenReturn("""
                        {
                          "title":"title",
                          "thumbnail_url":"thumb"
                        }
                        """);

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl(TEST_YOUTUBE_URL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        /*
         * author_name 누락도 title 누락과 동일하게 영구 실패로 단정하지 않는다.
         * Negative Cache는 401/403/404처럼 임베드 불가가 명확한 경우에만 저장한다.
         */
        verify(valueOperations, never()).set(
                eq(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID)),
                anyString(),
                any()
        );
    }

    @Test
    void validateYoutubeUrl_successCacheHit_returnsCachedMetadata() {
        YoutubeMetadata cached = new YoutubeMetadata(TEST_VIDEO_ID, "title", "artist", "thumb", null);
        String serialized = jsonMapper.writeValueAsString(cached);

        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))).thenReturn(serialized);

        YoutubeMetadata result = youtubeValidationService.validateYoutubeUrl(TEST_YOUTUBE_URL);

        assertThat(result.videoId()).isEqualTo(TEST_VIDEO_ID);
        assertThat(result.title()).isEqualTo("title");
        assertThat(result.durationSeconds()).isNull();
        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_successFetch_storesSuccessCache() {
        String url = "https://youtu.be/" + TEST_VIDEO_ID;

        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId(TEST_VIDEO_ID))
                .thenReturn("""
                        {
                          "title":"new title",
                          "author_name":"new artist",
                          "thumbnail_url":"thumb"
                        }
                        """);

        YoutubeMetadata result = youtubeValidationService.validateYoutubeUrl(url);

        assertThat(result.videoId()).isEqualTo(TEST_VIDEO_ID);
        assertThat(result.artist()).isEqualTo("new artist");
        assertThat(result.durationSeconds()).isNull();
        verify(valueOperations).set(anyString(), anyString(), any());
    }

    // ─────────────────────────────────────────────
    // URL 형식별 videoId 추출 + 케이스 보존 검증
    // ─────────────────────────────────────────────

    @Test
    void extractVideoId_standardUrl_extractsCorrectId() {
        stubSuccess(MIXED_CASE_ID);

        YoutubeMetadata result = youtubeValidationService
                .validateYoutubeUrl("https://www.youtube.com/watch?v=" + MIXED_CASE_ID);

        assertThat(result.videoId()).isEqualTo(MIXED_CASE_ID);
        verify(youtubeOEmbedClient).fetchByVideoId(eq(MIXED_CASE_ID));
    }

    @Test
    void extractVideoId_shortUrl_extractsCorrectId() {
        stubSuccess(MIXED_CASE_ID);

        YoutubeMetadata result = youtubeValidationService
                .validateYoutubeUrl("https://youtu.be/" + MIXED_CASE_ID);

        assertThat(result.videoId()).isEqualTo(MIXED_CASE_ID);
        verify(youtubeOEmbedClient).fetchByVideoId(eq(MIXED_CASE_ID));
    }

    @Test
    void extractVideoId_shortsUrl_extractsCorrectId() {
        stubSuccess(MIXED_CASE_ID);

        YoutubeMetadata result = youtubeValidationService
                .validateYoutubeUrl("https://www.youtube.com/shorts/" + MIXED_CASE_ID);

        assertThat(result.videoId()).isEqualTo(MIXED_CASE_ID);
        verify(youtubeOEmbedClient).fetchByVideoId(eq(MIXED_CASE_ID));
    }

    @Test
    void extractVideoId_embedUrl_extractsCorrectId() {
        stubSuccess(MIXED_CASE_ID);

        YoutubeMetadata result = youtubeValidationService
                .validateYoutubeUrl("https://www.youtube.com/embed/" + MIXED_CASE_ID);

        assertThat(result.videoId()).isEqualTo(MIXED_CASE_ID);
        verify(youtubeOEmbedClient).fetchByVideoId(eq(MIXED_CASE_ID));
    }

    @Test
    void extractVideoId_mobileUrl_extractsCorrectId() {
        stubSuccess(MIXED_CASE_ID);

        YoutubeMetadata result = youtubeValidationService
                .validateYoutubeUrl("https://m.youtube.com/watch?v=" + MIXED_CASE_ID);

        assertThat(result.videoId()).isEqualTo(MIXED_CASE_ID);
        verify(youtubeOEmbedClient).fetchByVideoId(eq(MIXED_CASE_ID));
    }

    @Test
    void extractVideoId_preservesMixedCase_notLowercased() {
        // URI 파싱 경로에서도 videoId가 소문자화되지 않는지 검증한다.
        stubSuccess(MIXED_CASE_ID);

        youtubeValidationService.validateYoutubeUrl("https://youtu.be/" + MIXED_CASE_ID);

        verify(youtubeOEmbedClient, never()).fetchByVideoId(eq(MIXED_CASE_ID.toLowerCase()));
        verify(youtubeOEmbedClient).fetchByVideoId(eq(MIXED_CASE_ID));
    }

    @Test
    void extractVideoId_tooShortId_badRequest() {
        assertThatThrownBy(() -> youtubeValidationService
                .validateYoutubeUrl("https://www.youtube.com/watch?v=abcdefg"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void extractVideoId_tooLongId_badRequest() {
        assertThatThrownBy(() -> youtubeValidationService
                .validateYoutubeUrl("https://www.youtube.com/watch?v=abcdefghijklm"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    // ─────────────────────────────────────────────
    // batch 검증 (validateYoutubeUrls)
    // ─────────────────────────────────────────────

    @Test
    void validateYoutubeUrls_emptyList_returnsEmptyWithoutRedisOrOembed() {
        assertThat(youtubeValidationService.validateYoutubeUrls(List.of())).isEmpty();

        verify(redisTemplate, never()).opsForValue();
        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrls_duplicateVideoId_validatesOnceAndMapsAllUrls() {
        // 같은 videoId를 가리키는 서로 다른 URL 형식은 한 번만 검증되어야 한다.
        String watchUrl = "https://www.youtube.com/watch?v=" + MIXED_CASE_ID;
        String shortUrl = "https://youtu.be/" + MIXED_CASE_ID;

        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedSuccessKey(MIXED_CASE_ID))))
                .thenReturn(singletonNullList());
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedFailureKey(MIXED_CASE_ID))))
                .thenReturn(singletonNullList());
        when(youtubeOEmbedClient.fetchByVideoId(MIXED_CASE_ID)).thenReturn(OEMBED_RESPONSE);

        Map<String, YoutubeMetadata> result =
                youtubeValidationService.validateYoutubeUrls(List.of(watchUrl, shortUrl));

        assertThat(result).containsOnlyKeys(watchUrl, shortUrl);
        assertThat(result.get(watchUrl).videoId()).isEqualTo(MIXED_CASE_ID);
        assertThat(result.get(shortUrl)).isSameAs(result.get(watchUrl));
        verify(youtubeOEmbedClient, times(1)).fetchByVideoId(MIXED_CASE_ID);
    }

    @Test
    void validateYoutubeUrls_successCacheHit_restoresWithoutOembed() {
        YoutubeMetadata cached = new YoutubeMetadata(TEST_VIDEO_ID, "title", "artist", "thumb", null);
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))))
                .thenReturn(List.of(jsonMapper.writeValueAsString(cached)));

        Map<String, YoutubeMetadata> result =
                youtubeValidationService.validateYoutubeUrls(List.of(TEST_YOUTUBE_URL));

        assertThat(result.get(TEST_YOUTUBE_URL).title()).isEqualTo("title");
        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrls_failureCacheHit_failsEntireBatch() {
        // 한 videoId라도 negative cache hit이면 전체 batch가 실패해야 한다.
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))))
                .thenReturn(singletonNullList());
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID))))
                .thenReturn(List.of("1"));

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrls(List.of(TEST_YOUTUBE_URL)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrls_mixedCacheState_callsOembedOnlyForMiss() {
        String cachedUrl = "https://www.youtube.com/watch?v=" + TEST_VIDEO_ID;
        String missUrl = "https://www.youtube.com/watch?v=" + MIXED_CASE_ID;
        YoutubeMetadata cached = new YoutubeMetadata(TEST_VIDEO_ID, "cached", "artist", "thumb", null);

        // distinct videoId 순서: [TEST_VIDEO_ID(hit), MIXED_CASE_ID(miss)]
        when(valueOperations.multiGet(List.of(
                RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID),
                RedisKeys.youtubeOembedSuccessKey(MIXED_CASE_ID))))
                .thenReturn(java.util.Arrays.asList(jsonMapper.writeValueAsString(cached), null));
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedFailureKey(MIXED_CASE_ID))))
                .thenReturn(singletonNullList());
        when(youtubeOEmbedClient.fetchByVideoId(MIXED_CASE_ID)).thenReturn(OEMBED_RESPONSE);

        Map<String, YoutubeMetadata> result =
                youtubeValidationService.validateYoutubeUrls(List.of(cachedUrl, missUrl));

        assertThat(result.get(cachedUrl).title()).isEqualTo("cached");
        assertThat(result.get(missUrl).videoId()).isEqualTo(MIXED_CASE_ID);
        verify(youtubeOEmbedClient, never()).fetchByVideoId(TEST_VIDEO_ID);
        verify(youtubeOEmbedClient, times(1)).fetchByVideoId(MIXED_CASE_ID);
    }

    @Test
    void validateYoutubeUrls_invalidUrl_failsBeforeRedis() {
        assertThatThrownBy(() -> youtubeValidationService
                .validateYoutubeUrls(List.of("https://example.com/watch?v=abc")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(valueOperations, never()).multiGet(any());
        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrls_embedNotAllowedDuringFetch_propagatesAndCachesFailure() {
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedSuccessKey(TEST_VIDEO_ID))))
                .thenReturn(singletonNullList());
        when(valueOperations.multiGet(List.of(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID))))
                .thenReturn(singletonNullList());
        when(youtubeOEmbedClient.fetchByVideoId(TEST_VIDEO_ID))
                .thenThrow(new io.github.ascrew.monomatbe.domain.youtube.exception
                        .YoutubeEmbedNotAllowedException("임베드 가능한 YouTube 영상이 아닙니다."));

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrls(List.of(TEST_YOUTUBE_URL)))
                .isInstanceOf(io.github.ascrew.monomatbe.domain.youtube.exception.YoutubeEmbedNotAllowedException.class);

        verify(valueOperations).set(eq(RedisKeys.youtubeOembedFailureKey(TEST_VIDEO_ID)), anyString(), any());
    }

    /** Mockito가 {@code List.of(null)}을 허용하지 않으므로 null 하나를 담는 헬퍼. */
    private java.util.List<String> singletonNullList() {
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add(null);
        return list;
    }

    /**
     * 정상 oEmbed 응답을 반환하도록 공통 mock을 구성한다.
     *
     * [사용 목적]
     * videoId 추출 테스트들은 oEmbed 파싱 자체가 목적이 아니다.
     * 따라서 성공 응답을 공통으로 stub 처리하고,
     * 각 테스트에서는 URL 형식별 videoId 추출 결과만 검증한다.
     */
    private void stubSuccess(String videoId) {
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(videoId))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(videoId))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId(videoId)).thenReturn(OEMBED_RESPONSE);
    }
}