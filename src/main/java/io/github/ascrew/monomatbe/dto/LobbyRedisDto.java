package io.github.ascrew.monomatbe.dto;

import lombok.*;

/**
 * [Lobby Redis DTO] Redis Hash에 저장할 로비 메타 정보 객체.
 * MySQL의 GAME_LOBBY 테이블 정보와 실시간 상태 정보를 통합 관리함.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LobbyRedisDto {
    private String code;          // 로비 초대 코드 (6자리 난수/문자)
    private String hostId;        // 현재 방장의 고유 식별자 (userId)
    private String title;         // 로비 제목
    private Long mapId;           // 선택된 맵 세트 ID
    private Integer maxPlayers;   // 최대 참여 가능 인원
    private Boolean isPrivate;    // 공개/비공개 여부 (목록 노출 제어용)
    private String status;        // 로비 상태 (WAITING, PLAYING 등)
}
