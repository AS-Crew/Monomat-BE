package io.github.ascrew.monomatbe.domain.youtube.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// 401/403/404 같이 oEmbed 단계에서 명확히 임베드 불가능으로 판정된 영구적 실패만을 의미한다.
// 5xx, 타임아웃, JSON 파싱 실패 등 일시적/불확실한 오류는 이 타입을 던지지 않는다.
// YoutubeValidationService 는 본 타입이 잡힐 때에만 Negative Cache 에 기록한다.
public class YoutubeEmbedNotAllowedException extends ResponseStatusException {

    public YoutubeEmbedNotAllowedException(String reason) {
        super(HttpStatus.BAD_REQUEST, reason);
    }
}
