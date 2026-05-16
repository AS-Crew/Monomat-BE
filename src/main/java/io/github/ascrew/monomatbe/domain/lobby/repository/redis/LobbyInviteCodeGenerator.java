package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 로비 초대 코드 생성을 담당하는 컴포넌트
 *
 * [정책]
 * - 초대 코드 길이와 문자셋은 기존 LobbyDefaults 계약을 그대로 사용한다.
 * - 코드 생성 알고리즘만 이동하며, Redis key 구조나 Lua script 계약은 변경하지 않는다.
 * - Random 대신 SecureRandom을 사용하여 코드 예측 가능성을 낮춘다.
 */
@Component
public class LobbyInviteCodeGenerator {

    /**
     * 초대 코드 생성용 보안 난수 생성기
     *
     * [SecureRandom 사용 이유]
     * 일반 Random은 예측 가능성이 상대적으로 높다.
     * 초대 코드는 공개/비공개 로비 입장 경로로 사용되므로,
     * 비공개 로비 코드 추측 가능성을 낮추기 위해 SecureRandom을 유지한다.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * LobbyDefaults 상수 기반으로 초대 코드를 생성한다.
     *
     * @return LobbyDefaults.INVITE_CODE_LENGTH 길이의 초대 코드
     */
    public String generate() {
        StringBuilder code = new StringBuilder(LobbyDefaults.INVITE_CODE_LENGTH);

        for (int i = 0; i < LobbyDefaults.INVITE_CODE_LENGTH; i++) {
            int randomIndex = SECURE_RANDOM.nextInt(
                    LobbyDefaults.INVITE_CODE_CHARACTERS.length()
            );

            code.append(LobbyDefaults.INVITE_CODE_CHARACTERS.charAt(randomIndex));
        }

        return code.toString();
    }
}