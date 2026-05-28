package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 닉네임 정책 검증 컴포넌트
 *
 * [책임]
 * - 회원가입/게스트 로그인에서 공통으로 사용하는 닉네임 정책을 검증한다.
 * - 현재는 DB 기반 금칙어 포함 여부를 검증한다.
 *
 * [설계 의도]
 * - RegisterAuthService, GuestAuthService 내부에 금칙어 조회/비교 로직을 넣지 않는다.
 * - 닉네임 검증 정책을 별도 컴포넌트로 분리하여 인증 서비스의 책임을 줄인다.
 *
 * [주의]
 * - null/blank/길이 검증은 기존 DTO Validation 및 각 서비스의 normalizeNickname()에서 처리한다.
 * - 이 컴포넌트에는 trim 및 길이 검증이 끝난 닉네임을 전달한다.
 */
@Component
@RequiredArgsConstructor
public class NicknamePolicyValidator {

    private final ForbiddenNicknameService forbiddenNicknameService;

    /**
     * 닉네임 정책을 검증한다.
     *
     * @param nickname 정규화 및 길이 검증이 완료된 닉네임
     * @throws AuthException 금칙어가 포함된 경우
     */
    public void validate(String nickname) {
        if (forbiddenNicknameService.containsForbiddenWord(nickname)) {
            throw new AuthException(AuthErrorCode.AUTH_NICKNAME_FORBIDDEN_WORD);
        }
    }
}