package io.github.ascrew.monomatbe.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LobbyRedisDto {
    private String code;          // 로비 초대 코드
    private String hostId;        // 현재 방장의 고유 식별자 (userId)
    private String title;         // 로비 제목
    private Long mapId;           // 선택된 맵 세트 ID
    private Integer maxPlayers;   // 최대 참여 가능 인원
    private Boolean isPrivate;    // 공개/비공개 여부
    private String status;        // 로비 상태 (WAITING, PLAYING 등)
}