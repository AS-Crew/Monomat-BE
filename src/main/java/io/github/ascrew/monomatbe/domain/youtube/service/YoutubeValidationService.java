package io.github.ascrew.monomatbe.domain.youtube.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.youtube.client.YoutubeOEmbedClient;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class YoutubeValidationService {

    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵 문제를 관리할 수 있습니다.";
    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_INVALID_YOUTUBE_URL = "유효한 YouTube URL이 아닙니다.";
    private static final String ERROR_INVALID_YOUTUBE_VIDEO = "임베드 가능한 YouTube 영상이 아닙니다.";

    private static final Duration OEMBED_SUCCESS_TTL = Duration.ofHours(6);
    private static final Duration OEMBED_FAILURE_TTL = Duration.ofMinutes(30);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final StringRedisTemplate redisTemplate;
    @Qualifier("pubSubJsonMapper") private final JsonMapper jsonMapper;
    private final YoutubeOEmbedClient youtubeOEmbedClient;

    public YoutubeMetadata validateForAuthoring(CustomPrincipal principal, String youtubeUrl) {
        validateRegisteredPrincipal(principal);
        return validateYoutubeUrl(youtubeUrl);
    }

    public YoutubeMetadata validateYoutubeUrl(String youtubeUrl) {
        String normalizedUrl = normalizeUrl(youtubeUrl);
        String videoId = extractVideoId(normalizedUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_URL));

        String successKey = RedisKeys.youtubeOembedSuccessKey(videoId);
        String failureKey = RedisKeys.youtubeOembedFailureKey(videoId);

        String successCached = redisTemplate.opsForValue().get(successKey);
        if (successCached != null) {
            return deserializeMetadata(successCached);
        }

        // Negative cache hit 시 외부 호출을 차단하고 즉시 거절한다.
        if (Boolean.TRUE.equals(redisTemplate.hasKey(failureKey))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_VIDEO);
        }

        try {
            String body = youtubeOEmbedClient.fetchByVideoId(videoId);
            YoutubeMetadata metadata = parseMetadata(videoId, body);
            redisTemplate.opsForValue().set(successKey, serializeMetadata(metadata), OEMBED_SUCCESS_TTL);
            return metadata;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().equals(HttpStatus.BAD_REQUEST)) {
                redisTemplate.opsForValue().set(failureKey, "1", OEMBED_FAILURE_TTL);
            }
            throw e;
        }
    }

    private YoutubeMetadata deserializeMetadata(String raw) {
        return jsonMapper.readValue(raw, YoutubeMetadata.class);
    }

    private String serializeMetadata(YoutubeMetadata metadata) {
        return jsonMapper.writeValueAsString(metadata);
    }

    private YoutubeMetadata parseMetadata(String videoId, String body) {
        JsonNode root = jsonMapper.readTree(body);
        String title = readText(root, "title");
        String artist = readText(root, "author_name");
        String thumbnailUrl = readText(root, "thumbnail_url");

        // oEmbed 응답에서 title/author_name이 비어있으면 사용 불가능한 영상으로 간주한다.
        // 그대로 저장 시 맵 문제의 제목/아티스트가 null/blank로 노출되므로 검증 단계에서 차단한다.
        if (isBlank(title) || isBlank(artist)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_VIDEO);
        }

        return new YoutubeMetadata(videoId, title, artist, thumbnailUrl);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String normalizeUrl(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_URL);
        }
        return youtubeUrl.trim();
    }

    private Optional<String> extractVideoId(String youtubeUrl) {
        URI uri;
        try {
            uri = URI.create(youtubeUrl);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String host = Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }

        if ("youtu.be".equals(host)) {
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            String candidate = path.startsWith("/") ? path.substring(1) : path;
            if (candidate.contains("/")) {
                candidate = candidate.substring(0, candidate.indexOf('/'));
            }
            return isValidVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        if ("youtube.com".equals(host) || "m.youtube.com".equals(host)) {
            String path = Optional.ofNullable(uri.getPath()).orElse("");

            if (path.startsWith("/watch")) {
                String query = Optional.ofNullable(uri.getRawQuery()).orElse("");
                for (String part : query.split("&")) {
                    if (part.startsWith("v=")) {
                        String candidate = part.substring(2);
                        return isValidVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
                    }
                }
                return Optional.empty();
            }

            if (path.startsWith("/shorts/")) {
                String candidate = path.substring("/shorts/".length());
                if (candidate.contains("/")) {
                    candidate = candidate.substring(0, candidate.indexOf('/'));
                }
                return isValidVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
            }

            if (path.startsWith("/embed/")) {
                String candidate = path.substring("/embed/".length());
                if (candidate.contains("/")) {
                    candidate = candidate.substring(0, candidate.indexOf('/'));
                }
                return isValidVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
            }
        }

        return Optional.empty();
    }

    private boolean isValidVideoId(String value) {
        return value != null && VIDEO_ID_PATTERN.matcher(value).matches();
    }

    private void validateRegisteredPrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_PRINCIPAL);
        }
        if (principal.userType() != UserType.REGISTERED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ERROR_REGISTERED_ONLY);
        }
    }
}
