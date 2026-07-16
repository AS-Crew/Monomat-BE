package io.github.ascrew.monomatbe.domain.youtube.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.youtube.YoutubeVideoId;
import io.github.ascrew.monomatbe.domain.youtube.client.YoutubeOEmbedClient;
import io.github.ascrew.monomatbe.domain.youtube.exception.YoutubeEmbedNotAllowedException;
import io.github.ascrew.monomatbe.domain.youtube.model.YoutubeMetadata;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class YoutubeValidationService {

    private static final String ERROR_REGISTERED_ONLY = "정식 회원만 맵 문제를 관리할 수 있습니다.";
    private static final String ERROR_INVALID_PRINCIPAL = "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_INVALID_YOUTUBE_URL = "유효한 YouTube URL이 아닙니다.";
    private static final String ERROR_INVALID_YOUTUBE_VIDEO = "임베드 가능한 YouTube 영상이 아닙니다.";
    private static final String ERROR_UPSTREAM_RESPONSE = "YouTube 검증 서버 응답이 비정상입니다.";
    private static final String ERROR_OEMBED_BUSY = "YouTube 검증 요청이 많아 잠시 후 다시 시도해주세요.";
    private static final String ERROR_VALIDATION_INTERRUPTED = "YouTube 검증 요청이 중단되었습니다.";

    private static final Duration OEMBED_SUCCESS_TTL = Duration.ofHours(6);
    private static final Duration OEMBED_FAILURE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final YoutubeOEmbedClient youtubeOEmbedClient;

    // 애플리케이션 전역으로 공유되는 oEmbed 동시 호출 제한. 싱글턴 서비스에서 한 번만 생성해
    // 단건/batch 모든 cache-miss 경로의 oEmbed 호출 수를 batch-concurrency 값으로 제한한다.
    private final Semaphore oembedConcurrencyLimiter;

    // permit 획득 대기 상한. 초과 시 외부 호출을 시작하지 않고 503으로 빠르게 실패해
    // 트래픽 급증/YouTube 장애 시 대기 스레드·요청 컨텍스트가 무한정 누적되는 것을 막는다.
    private final Duration queueTimeout;

    public YoutubeValidationService(
            StringRedisTemplate redisTemplate,
            @Qualifier("pubSubJsonMapper") JsonMapper jsonMapper,
            YoutubeOEmbedClient youtubeOEmbedClient,
            @Value("${youtube.oembed.batch-concurrency:8}") int batchConcurrency,
            @Value("${youtube.oembed.queue-timeout:10s}") Duration queueTimeout
    ) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.youtubeOEmbedClient = youtubeOEmbedClient;
        this.oembedConcurrencyLimiter = new Semaphore(Math.max(1, batchConcurrency));
        this.queueTimeout = queueTimeout;
    }

    public YoutubeMetadata validateForAuthoring(CustomPrincipal principal, String youtubeUrl) {
        validateRegisteredPrincipal(principal);
        return validateYoutubeUrl(youtubeUrl);
    }

    public YoutubeMetadata validateYoutubeUrl(String youtubeUrl) {
        String videoId = resolveVideoId(youtubeUrl);

        String successCached = redisTemplate.opsForValue().get(RedisKeys.youtubeOembedSuccessKey(videoId));
        if (successCached != null) {
            return deserializeMetadata(successCached);
        }

        // Negative cache hit 시 외부 호출을 차단하고 즉시 거절한다.
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.youtubeOembedFailureKey(videoId)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_VIDEO);
        }

        return acquirePermitAndFetch(videoId);
    }

    /**
     * 여러 YouTube URL을 한 번에 검증한다. 단건 {@link #validateYoutubeUrl(String)}과 동일한 예외 정책을 따른다.
     *
     * <p>동일 요청 내 중복 videoId는 한 번만 검증하고, success/failure 캐시는 Redis {@code multiGet}으로 일괄 조회하며,
     * cache miss videoId에 대해서만 제한된 동시성으로 oEmbed API를 병렬 호출한다.
     * URL 하나라도 검증에 실패하면 전체 batch가 실패한다.</p>
     *
     * @param youtubeUrls 검증할 원본 YouTube URL 목록
     * @return 원본 URL → 검증된 메타데이터 매핑 (입력에 중복 URL이 있으면 같은 메타데이터를 공유)
     */
    public Map<String, YoutubeMetadata> validateYoutubeUrls(List<String> youtubeUrls) {
        if (youtubeUrls == null || youtubeUrls.isEmpty()) {
            return Map.of();
        }

        // 1. 원본 URL → videoId 매핑 구성 (유효하지 않으면 단건과 동일하게 즉시 BAD_REQUEST).
        //    LinkedHashMap/LinkedHashSet으로 요청 순서를 보존하고 중복 videoId를 제거한다.
        Map<String, String> urlToVideoId = new LinkedHashMap<>();
        for (String youtubeUrl : youtubeUrls) {
            urlToVideoId.computeIfAbsent(youtubeUrl, this::resolveVideoId);
        }
        List<String> distinctVideoIds = new ArrayList<>(new LinkedHashSet<>(urlToVideoId.values()));

        // 2. success 캐시 일괄 조회 후 hit 복원.
        Map<String, YoutubeMetadata> metadataByVideoId = new LinkedHashMap<>();
        List<String> successMissVideoIds = new ArrayList<>();
        List<String> successValues = redisTemplate.opsForValue()
                .multiGet(distinctVideoIds.stream().map(RedisKeys::youtubeOembedSuccessKey).toList());
        for (int i = 0; i < distinctVideoIds.size(); i++) {
            String cached = successValues == null ? null : successValues.get(i);
            if (cached != null) {
                metadataByVideoId.put(distinctVideoIds.get(i), deserializeMetadata(cached));
            } else {
                successMissVideoIds.add(distinctVideoIds.get(i));
            }
        }

        // 3. failure 캐시 일괄 조회 - hit이 하나라도 있으면 전체 batch 실패.
        if (!successMissVideoIds.isEmpty()) {
            List<String> failureValues = redisTemplate.opsForValue()
                    .multiGet(successMissVideoIds.stream().map(RedisKeys::youtubeOembedFailureKey).toList());
            if (failureValues != null && failureValues.stream().anyMatch(Objects::nonNull)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_VIDEO);
            }
        }

        // 4. cache miss videoId만 oEmbed 병렬 호출.
        //    - 실제 동시 호출 수는 전역 공유 Semaphore(oembedConcurrencyLimiter)로 제한한다.
        //    - 완료 순서로 결과를 회수해 하나라도 실패하면 즉시 남은 작업을 취소하고 중단한다.
        List<String> missVideoIds = successMissVideoIds;
        if (!missVideoIds.isEmpty()) {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletionService<OEmbedResult> completionService = new ExecutorCompletionService<>(executor);
                List<Future<OEmbedResult>> futures = new ArrayList<>(missVideoIds.size());
                for (String videoId : missVideoIds) {
                    futures.add(completionService.submit(
                            () -> new OEmbedResult(videoId, acquirePermitAndFetch(videoId))));
                }
                try {
                    for (int i = 0; i < missVideoIds.size(); i++) {
                        OEmbedResult result = completionService.take().get();
                        metadataByVideoId.put(result.videoId(), result.metadata());
                    }
                } catch (ExecutionException e) {
                    cancelOutstanding(futures, executor);
                    // fetchValidateAndCache가 던진 원본 예외(ResponseStatusException/YoutubeEmbedNotAllowedException)를
                    // 그대로 전파해 단건 검증과 예외 정책을 동일하게 유지한다.
                    throw toRuntimeFailure(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelOutstanding(futures, executor);
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ERROR_VALIDATION_INTERRUPTED, e);
                }
            }
        }

        // 5. 원본 URL → metadata 최종 매핑 조립 (요청 순서 보존).
        Map<String, YoutubeMetadata> result = new LinkedHashMap<>();
        urlToVideoId.forEach((url, videoId) -> result.put(url, metadataByVideoId.get(videoId)));
        return result;
    }

    private void cancelOutstanding(List<Future<OEmbedResult>> futures, ExecutorService executor) {
        // 아직 완료되지 않은 작업을 인터럽트로 취소하고 실행기를 종료해
        // 첫 실패 이후의 불필요한 oEmbed 호출을 즉시 중단한다.
        futures.forEach(future -> future.cancel(true));
        executor.shutdownNow();
    }

    private RuntimeException toRuntimeFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ERROR_UPSTREAM_RESPONSE, cause);
    }

    private record OEmbedResult(String videoId, YoutubeMetadata metadata) {
    }

    private String resolveVideoId(String youtubeUrl) {
        String normalizedUrl = normalizeUrl(youtubeUrl);
        return extractVideoId(normalizedUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_YOUTUBE_URL));
    }

    /**
     * 전역 공유 Semaphore permit을 획득한 뒤 단건/batch 공통 oEmbed 호출을 수행한다.
     *
     * <p>단건 검증과 batch task가 모두 이 helper를 거치므로 애플리케이션 전체 동시 oEmbed 호출 수가
     * 하나의 상한을 공유한다. permit을 {@code queueTimeout} 안에 얻지 못하면 외부 호출을 시작하지 않고
     * 즉시 503으로 실패해 대기 스레드/요청 컨텍스트가 무한정 누적되는 것을 막는다.</p>
     */
    private YoutubeMetadata acquirePermitAndFetch(String videoId) {
        boolean acquired;
        try {
            acquired = oembedConcurrencyLimiter.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ERROR_VALIDATION_INTERRUPTED, e);
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ERROR_OEMBED_BUSY);
        }
        try {
            return fetchValidateAndCache(videoId);
        } finally {
            oembedConcurrencyLimiter.release();
        }
    }

    private YoutubeMetadata fetchValidateAndCache(String videoId) {
        try {
            String body = youtubeOEmbedClient.fetchByVideoId(videoId);
            YoutubeMetadata metadata = parseMetadata(videoId, body);
            redisTemplate.opsForValue()
                    .set(RedisKeys.youtubeOembedSuccessKey(videoId), serializeMetadata(metadata), OEMBED_SUCCESS_TTL);
            return metadata;
        } catch (YoutubeEmbedNotAllowedException e) {
            // 401/403/404 같은 영구적 임베드 불가만 Negative Cache 에 기록한다.
            // 5xx/타임아웃/파싱 실패/빈 메타데이터 등 일시적 또는 불확실한 오류는 캐싱하지 않아
            // 업스트림 회복 후 재시도 시점에서 정상 응답을 받을 수 있도록 한다.
            redisTemplate.opsForValue().set(RedisKeys.youtubeOembedFailureKey(videoId), "1", OEMBED_FAILURE_TTL);
            throw e;
        } catch (JacksonException e) {
            log.warn("YouTube oEmbed 응답 파싱 실패 - videoId: {}", videoId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ERROR_UPSTREAM_RESPONSE, e);
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

        // YouTube oEmbed는 영상 길이를 제공하지 않는다.
        // durationSeconds는 향후 Data API, 별도 metadata resolver, IFrame 기반 사전 수집 등으로
        // 확보할 수 있을 때 채워 넣기 위해 nullable 필드로 유지한다.
        return new YoutubeMetadata(videoId, title, artist, thumbnailUrl, null);
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
        return YoutubeVideoId.isValid(value);
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