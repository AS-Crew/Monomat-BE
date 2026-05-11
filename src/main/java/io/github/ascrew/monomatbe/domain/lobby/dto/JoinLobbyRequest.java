package io.github.ascrew.monomatbe.domain.lobby.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 초대 코드 기반 로비 입장 요청 DTO
 * 검증 규칙 :
 * - inviteCode : 필수, 영문 대문자 + 숫자 조합 6가지
 * inviteCode 형식 검증 이유 :
 * 형식이 맞지 않는 코드는 Redis 조회 전에 즉시 거부하여 불필요한 외부 요청을 차단한다.
 */
public record JoinLobbyRequest(

        @NotBlank(message = "초대 코드는 비어 있을 수 없습니다.")
        @Size(min = 6, max = 6, message = "초대 코드는 6자리여야 합니다.")
        @Pattern(
                regexp = "^[A-Z0-9]{6}$",
                message = "초대 코드는 영문 대문자와 숫자만 허용됩니다."
        )
        String inviteCode
) {
}
