/*
 * 공개 로비 목록 응답의 단일 로비 아이템 DTO
 *
 * [설계 이유]
 * Redis Hash 매핑 전용 LobbyRedisDto와 API 응답 계약을 분리한다.
 * 방장 닉네임(hostNickname)은 Redis에 저장되지 않고 Auth 도메인 조회로 채워지는
 * enriched 값이므로, Redis 매핑 DTO에 두지 않고 응답 전용 DTO에만 둔다.
 */
package io.github.ascrew.monomatbe.domain.lobby.dto;

/**
 * 공개 로비 목록의 각 로비 아이템 응답 DTO
 *
 * @param code                 로비 초대 코드 (6자리 고유 코드)
 * @param hostId               현재 방장의 사용자 식별자 (userIdentifier)
 * @param hostNickname         방장 닉네임. 정식 회원/게스트 모두 내려오며, 조회 실패 시 fallback 값
 * @param title                로비 제목
 * @param mapId                선택된 맵 세트 ID (미선택 시 null)
 * @param mapTitle             선택된 맵 제목 (미선택 시 null)
 * @param mapCategory          선택된 맵의 카테고리 (미선택 시 null)
 * @param maxPlayers           최대 참여 가능 인원
 * @param currentPlayers       현재 참여 인원 수
 * @param isPrivate            공개(false) / 비공개(true) 여부
 * @param status               로비 상태 (WAITING, PLAYING)
 * @param questionCount        문제 수
 * @param timeLimitSeconds     제한 시간(초)
 * @param createdAtEpochMillis 로비 생성 시각 (epoch millis, null 가능)
 */
public record LobbyListItemResponse(
        String code,
        String hostId,
        String hostNickname,
        String title,
        Long mapId,
        String mapTitle,
        String mapCategory,
        Integer maxPlayers,
        Integer currentPlayers,
        Boolean isPrivate,
        String status,
        Integer questionCount,
        Integer timeLimitSeconds,
        Long createdAtEpochMillis
) {

    /**
     * Redis 조회 DTO와 별도로 조회한 방장 닉네임을 합쳐 응답 아이템을 생성한다.
     *
     * @param dto          Redis에서 조회한 로비 정보
     * @param hostNickname 별도 조회한 방장 닉네임 (fallback 포함)
     * @return 닉네임이 채워진 로비 목록 아이템 응답
     */
    public static LobbyListItemResponse of(LobbyRedisDto dto, String hostNickname) {
        return new LobbyListItemResponse(
                dto.getCode(),
                dto.getHostId(),
                hostNickname,
                dto.getTitle(),
                dto.getMapId(),
                dto.getMapTitle(),
                dto.getMapCategory(),
                dto.getMaxPlayers(),
                dto.getCurrentPlayers(),
                dto.getIsPrivate(),
                dto.getStatus(),
                dto.getQuestionCount(),
                dto.getTimeLimitSeconds(),
                dto.getCreatedAtEpochMillis()
        );
    }
}
