/*
 * Redis에 저장된 로비 정보를 클라이언트에 반환하기 위한 DTO
 * Redis Hash 구조의 데이터를 Java 객체로 매핑한다.
 */
package io.github.ascrew.monomatbe.domain.lobby.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LobbyRedisDto {

    private String code;          // 로비 초대 코드 (6자리 고유 코드)
    private String hostId;        // 현재 방장의 사용자 식별자
    private String title;         // 로비 제목

    private Long mapId;           // 선택된 맵 세트 ID (미선택 시 null)
    private String mapTitle;      // 선택된 맵 제목 (미선택 시 null)
    private String mapCategory;   // 선택된 맵의 카테고리 (미선택 시 null)

    private Integer maxPlayers;   // 최대 참여 가능 인원
    private Integer currentPlayers; // 현재 참여 인원 수
    private Boolean isPrivate;    // 공개(false) / 비공개(true) 여부
    private String status;        // 로비 상태 (WAITING, PLAYING)

    /*
     * 로비 생성 시각
     *
     * [정렬 목적]
     * Redis의 Set 자료구조는 삽입 순서를 보장하지 않는다.
     * 따라서 최신순 정렬을 위해 로비 생성 시점의 epoch millis 값을 Hash에 저장하고,
     * 목적 응답 DTO에도 함께 매핑한다.
     *
     * [기존 데이터 호환]
     * 해당 필드 도입 전 생성된 로비에는 값이 없을 수 있으므로 null을 허용한다.
     */
    private Long createdAtEpochMillis;
}