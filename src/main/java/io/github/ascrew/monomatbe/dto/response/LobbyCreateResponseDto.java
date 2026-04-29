package io.github.ascrew.monomatbe.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 로비 생성 API (POST /api/lobbies)의 응답 바디 DTO

// [필드 설명]
// - lobbyId : DB에 저장된 로비의 PK, 이후 로비 관련 API 호출 시 식별자로 활용
// - inviteCode : 발급된 6자리 초대 코드
// - title : 생성된 로비 제목
// - maxPlayers : 최대 참여 가능 인원
// - isPrivate : 공개/비공개 여부
// - deepLink : 프론트엔드가 클립보드 복사 기능에 활용할 딥링크 경로
//              예시 : /lobby/AB3K9Z

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LobbyCreateResponseDto {

    // DB에 저장된 로비의 PK
    private Long lobbyId;

    // 발급된 6자리 초대 코드
    // 비공개 로비의 유일한 입장 수단이며, 공개 로비의 빠른 참여 수단으로도 활용
    private String inviteCode;

    // 생성된 로비 이름 (제목)
    private String title;

    // 최대 참여 가능 인원
    private Integer maxPlayers;

    // 공개 (false) / 비공개 (true) 여부
    private Boolean isPrivate;

    // 프론트엔드가 클립보드 복사 기능에 활용할 딥링크 경로
    // 서버에서 "/lobby/" + inviteCode 형태로 조립하여 반환
    private String deepLink;
}