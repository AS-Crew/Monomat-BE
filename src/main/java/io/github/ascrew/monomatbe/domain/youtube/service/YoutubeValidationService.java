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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
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
    private static final Pattern FALLBACK_WATCH_PATTERN = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern FALLBACK_YOUTU_BE_PATTERN = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");

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
        String failureKey = RedisKeys.youtubeOembedFailureKey(hashUrl(normalizedUrl));
        String successKey = RedisKeys.youtubeOembedSuccessKey(videoId);

        String successCached = redisTemplate.opsForValue().get(successKey);
        if (successCached != null) {
            return deserializeMetadata(successCached);
        }
        boolean hadFailureCache = redisTemplate.hasKey(failureKey) == Boolean.TRUE;

        try {
            String body = youtubeOEmbedClient.fetchByVideoId(videoId);
            YoutubeMetadata metadata = parseMetadata(videoId, body);
            redisTemplate.opsForValue().set(successKey, serializeMetadata(metadata), OEMBED_SUCCESS_TTL);
            if (hadFailureCache) {
                redisTemplate.delete(failureKey);
            }
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
        return new YoutubeMetadata(videoId, title, artist, thumbnailUrl);
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
        try {
            URI uri = URI.create(youtubeUrl);
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
        } catch (Exception ignored) {
            // URL 파싱 실패 시 하위 fallback 로직으로 진행
        }

        // fallback: 원본 URL에서 케이스 보존 상태로 추출 (소문자화 금지 — videoId는 대소문자 구분)
        Matcher watchMatcher = FALLBACK_WATCH_PATTERN.matcher(youtubeUrl);
        if (watchMatcher.find()) {
            String candidate = watchMatcher.group(1);
            return isValidVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        Matcher youtubeBeMatcher = FALLBACK_YOUTU_BE_PATTERN.matcher(youtubeUrl);
        if (youtubeBeMatcher.find()) {
            String candidate = youtubeBeMatcher.group(1);
            return isValidVideoId(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        return Optional.empty();
    }

    private boolean isValidVideoId(String value) {
        return value != null && VIDEO_ID_PATTERN.matcher(value).matches();
    }

    private String hashUrl(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "URL 해시 생성에 실패했습니다.");
        }
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
