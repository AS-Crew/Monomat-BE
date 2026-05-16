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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YoutubeValidationServiceTest {

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
        youtubeValidationService = new YoutubeValidationService(redisTemplate, jsonMapper, youtubeOEmbedClient);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void validateYoutubeUrl_invalidFormat_badRequest() {
        // example.com host는 YouTube 도메인이 아니므로 거절되어야 함
        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl("https://example.com/watch?v=abc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_nonYoutubeDomainWithValidVideoId_badRequest() {
        // 11자 유효 videoId가 있더라도 host가 YouTube 도메인이 아니면 거절되어야 함
        assertThatThrownBy(() -> youtubeValidationService
                .validateYoutubeUrl("https://example.com/watch?v=dQw4w9WgXcQ"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("유효한 YouTube URL이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_negativeCacheHit_blocksWithoutRevalidate() {
        String url = "https://www.youtube.com/watch?v=abcde123456";
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey("abcde123456"))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey("abcde123456"))).thenReturn(true);

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl(url))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_oembedReturnsBlankTitle_badRequestAndStoresFailureCache() {
        String url = "https://www.youtube.com/watch?v=abcde123456";
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey("abcde123456"))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey("abcde123456"))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId("abcde123456"))
                .thenReturn("""
                        {
                          "author_name":"artist",
                          "thumbnail_url":"thumb"
                        }
                        """);

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl(url))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        verify(valueOperations).set(eq(RedisKeys.youtubeOembedFailureKey("abcde123456")), anyString(), any());
    }

    @Test
    void validateYoutubeUrl_oembedReturnsBlankArtist_badRequestAndStoresFailureCache() {
        String url = "https://www.youtube.com/watch?v=abcde123456";
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey("abcde123456"))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey("abcde123456"))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId("abcde123456"))
                .thenReturn("""
                        {
                          "title":"title",
                          "thumbnail_url":"thumb"
                        }
                        """);

        assertThatThrownBy(() -> youtubeValidationService.validateYoutubeUrl(url))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("임베드 가능한 YouTube 영상이 아닙니다.");

        verify(valueOperations).set(eq(RedisKeys.youtubeOembedFailureKey("abcde123456")), anyString(), any());
    }

    @Test
    void validateYoutubeUrl_successCacheHit_returnsCachedMetadata() throws Exception {
        YoutubeMetadata cached = new YoutubeMetadata("abcde123456", "title", "artist", "thumb");
        String serialized = jsonMapper.writeValueAsString(cached);

        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey("abcde123456"))).thenReturn(serialized);

        YoutubeMetadata result = youtubeValidationService
                .validateYoutubeUrl("https://www.youtube.com/watch?v=abcde123456");

        assertThat(result.videoId()).isEqualTo("abcde123456");
        assertThat(result.title()).isEqualTo("title");
        verify(youtubeOEmbedClient, never()).fetchByVideoId(anyString());
    }

    @Test
    void validateYoutubeUrl_successFetch_storesSuccessCache() {
        String url = "https://youtu.be/abcde123456";

        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey("abcde123456"))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey("abcde123456"))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId("abcde123456"))
                .thenReturn("""
                        {
                          "title":"new title",
                          "author_name":"new artist",
                          "thumbnail_url":"thumb"
                        }
                        """);

        YoutubeMetadata result = youtubeValidationService.validateYoutubeUrl(url);

        assertThat(result.videoId()).isEqualTo("abcde123456");
        assertThat(result.artist()).isEqualTo("new artist");
        verify(valueOperations).set(anyString(), anyString(), any());
    }

    // ─────────────────────────────────────────────
    // URL 형식별 videoId 추출 + 케이스 보존 검증
    // ─────────────────────────────────────────────

    private static final String MIXED_CASE_ID = "dQw4w9WgXcQ";
    private static final String OEMBED_RESPONSE =
            "{\"title\":\"t\",\"author_name\":\"a\",\"thumbnail_url\":\"th\"}";

    private void stubSuccess(String videoId) {
        when(valueOperations.get(RedisKeys.youtubeOembedSuccessKey(videoId))).thenReturn(null);
        when(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(videoId))).thenReturn(false);
        when(youtubeOEmbedClient.fetchByVideoId(videoId)).thenReturn(OEMBED_RESPONSE);
    }

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
        // URI 파싱 경로에서도 videoId가 소문자화되지 않는지 검증
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
}
