package io.github.ascrew.monomatbe.dto;

import lombok.Builder;

@Builder
public record LobbyRedisDto (
    String code,          // 로비 초대 코드
    String hostId,        // 현재 방장의 고유 식별자 (userId)
    String title,         // 로비 제목
    Long mapId,           // 선택된 맵 세트 ID
    Integer maxPlayers,   // 최대 참여 가능 인원
    Boolean is,    // 공개/비공개 여부
    String status        // 로비 상태 (WAITING, PLAYING 등)
){}