package io.github.ascrew.monomatbe.domain.lobby.dto;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 공개 로비 목록 조회 페이징 요청 값
 *
 * [책임]
 * - page/size 요청 파라미터 기본값 적용
 * - 음수 page 차단
 * - 1 미만 size 차단
 * - 과도한 size 요청 차단
 *
 * [설계 이유]
 * Controller나 Service에 페이징 검증 숫자를 흩뿌리지 않고,
 * 페이징 정책을 하나의 값 객체에 모은다.
 *
 * [현재 단계]
 * 1단계에서는 Java 메모리에서 필터/정렬이 끝난 뒤 slice 용도로 사용한다.
 * 이후 Redis ZSET 기반 조회로 전환할 때도 동일한 page/size 정책을 재사용한다.
 */
public record LobbyPageRequest(
        int page,
        int size
) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private static final String ERROR_INVALID_PAGE =
            "page는 0 이상이어야 합니다.";
    private static final String ERROR_INVALID_SIZE =
            "size는 1 이상이어야 합니다.";
    private static final String ERROR_SIZE_TOO_LARGE =
            "size는 최대 " + MAX_SIZE + "까지 요청할 수 있습니다.";

    public static LobbyPageRequest of(
            Integer page,
            Integer size
    ) {
        int normalizedPage = page != null ? page : DEFAULT_PAGE;
        int normalizedSize = size != null ? size : DEFAULT_SIZE;

        validatePage(normalizedPage);
        validateSize(normalizedSize);

        return new LobbyPageRequest(
                normalizedPage,
                normalizedSize
        );
    }

    private static void validatePage(int page) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_PAGE
            );
        }
    }

    private static void validateSize(int size) {
        if (size < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_INVALID_SIZE
            );
        }

        if (size > MAX_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_SIZE_TOO_LARGE
            );
        }
    }

    /**
     * 0-based page를 List.subList에 사용할 시작 index로 변환한다.
     *
     * [주의]
     * page * size 계산은 int overflow를 피하기 위해 long으로 수행한다.
     * 현재 MAX_SIZE가 100이라 현실적으로 overflow 가능성은 낮지만,
     * 요청 파라미터는 외부 입력이므로 안전하게 처리한다.
     */
    public long offset() {
        return (long) page * size;
    }
}