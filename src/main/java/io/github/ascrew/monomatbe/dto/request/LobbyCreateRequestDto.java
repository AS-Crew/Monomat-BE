package io.github.ascrew.monomatbe.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 로비 생성 API (POST /api/lobbies)의 요청 바디 DTO

// [필드 설명]
// - title : 로비 이름 (제목), 로비 목록 화면에 노출되는 이름
// - maxPlayers : 최대 참여 가능 인원 (방장 포함 인원수)
// - isPrivate : 공개 (false) / 비공개 (true) 여부
//               true면 로비 목록에 노출되지 않고 초대 코드로만 입장 가능

@Getter
@NoArgsConstructor
public class LobbyCreateRequestDto {

    // 로비 이름 (제목)
    // @NotBlank : null, 빈 문자열 (""), 공백만 있는 문자열 (" ") 모두 차단
    // @NotEmpty와 달리 공백 문자열까지 잡아주므로 이름 (제목) 용도에 더 적절
    @NotBlank(message = "로비 제목은 필수입니다.")
    private String title;

    // 최대 참여 가능 인원
    // @NotNull : maxPlayers 자체가 null인 경우 차단
    // @Min(2) : 방장 혼자만 있는 로비는 게임이 불가능하므로 최소 2명
    // @MAX(10) : 기능명세서에 명시된 최대 인원 기준, 춯수 조정 가능
    @NotNull(message = "최대 인원은 필수입니다.")
    @Min(value = 2, message = "최소 인원은 2명입니다.")
    @Max(value = 10, message = "최대 인원은 10명입니다.")
    private Integer maxPlayers;

    // 공개 (false) / 비공개 (true) 여부
    // @NotNull : true/false 중 하나를 반드시 명시하도록 강제
    // null 허용 시 서비스 레이어에서 기본값 처리가 필요해져서 로직이 복잡해짐
    @NotNull(message = "공개 여부는 필수입니다.")
    private Boolean isPrivate;
}