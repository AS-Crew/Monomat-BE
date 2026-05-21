package io.github.ascrew.monomatbe.domain.lobby.dto;

import java.util.List;

/**
 * 공개 로비 목록 페이징 응답 DTO
 *
 * @param items   현재 페이지에 포함된 데이터
 * @param page    0-based 현재 페이지 번호
 * @param size    요청한 페이지 크기
 * @param hasNext 다음 페이지 존재 여부
 * @param <T>     목록 아이템 타입
 *
 * [설계 이유]
 * 기존 GET /api/lobbies는 단순 배열을 반환했기 때문에
 * FE가 다음 페이지 존재 여부를 판단할 수 없었다.
 *
 * 이 DTO를 사용하면 현재 단계의 Java slice 방식뿐 아니라,
 * 이후 Redis ZSET에서 size + 1개를 조회하는 방식으로 전환할 때도
 * 동일한 응답 계약을 유지할 수 있다.
 */
public record LobbyPageResponse<T>(
        List<T> items,
        int page,
        int size,
        boolean hasNext
) {

    public static <T> LobbyPageResponse<T> of(
            List<T> items,
            LobbyPageRequest pageRequest,
            boolean hasNext
    ) {
        return new LobbyPageResponse<>(
                List.copyOf(items),
                pageRequest.page(),
                pageRequest.size(),
                hasNext
        );
    }
}