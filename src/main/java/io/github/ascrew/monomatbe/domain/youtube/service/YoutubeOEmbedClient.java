package io.github.ascrew.monomatbe.domain.youtube.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class YoutubeOEmbedClient {

    private static final String OEMBED_TEMPLATE = "https://www.youtube.com/oembed?url=%s&format=json";
    private static final String YOUTUBE_WATCH_URL = "https://www.youtube.com/watch?v=%s";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public String fetchByVideoId(String videoId) {
        String canonicalUrl = YOUTUBE_WATCH_URL.formatted(videoId);
        String encodedUrl = URLEncoder.encode(canonicalUrl, StandardCharsets.UTF_8);
        String endpoint = OEMBED_TEMPLATE.formatted(encodedUrl);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return response.body();
            }

            if (statusCode == 401 || statusCode == 403 || statusCode == 404) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "임베드 가능한 YouTube 영상이 아닙니다.");
            }

            log.error("YouTube oEmbed 호출 실패 - status: {}", statusCode);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "YouTube 검증 서버 응답이 비정상입니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "YouTube 검증 요청이 중단되었습니다.");
        } catch (IOException e) {
            log.error("YouTube oEmbed 호출 중 네트워크 오류", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "YouTube 검증 서버와 통신할 수 없습니다.");
        }
    }
}
