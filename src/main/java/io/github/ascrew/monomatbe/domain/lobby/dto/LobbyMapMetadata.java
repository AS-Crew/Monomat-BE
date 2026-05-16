package io.github.ascrew.monomatbe.domain.lobby.dto;

/**
 * 로비에 연결된 맵의 최소 메타데이터
 *
 * [설계 의도]
 * LobbyRepository는 Redis 저장 책임만 가지므로 QuizMap 엔티티에 직접 의존하지 않는다.
 * LobbyMapPolicy가 맵 접근 권한을 검증한 뒤, Redis Hash에 필요한 최소 정보만 이 DTO로 전달한다.
 */
public record LobbyMapMetadata(
        Long mapId,
        String mapTitle,
        String mapCategory
) {
}