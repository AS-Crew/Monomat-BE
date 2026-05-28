package io.github.ascrew.monomatbe.domain.auth.service;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 닉네임 및 금칙어 비교용 정규화 컴포넌트
 *
 * [책임]
 * - 닉네임과 금칙어를 동일한 기준으로 비교할 수 있도록 정규화한다.
 *
 * [정규화 정책]
 * - 앞뒤 공백 제거
 * - 대소문자 차이 제거
 * - 모든 공백 문자 제거
 *
 * [예시]
 * - "Admin"      -> "admin"
 * - "A d m i n"  -> "admin"
 * - "관 리 자"    -> "관리자"
 *
 * [주의]
 * - 이 컴포넌트는 필수값 검증을 하지 않는다.
 * - null/blank 여부는 호출하는 서비스에서 도메인 정책에 맞게 판단한다.
 */
@Component
public class NicknameNormalizer {

    public String normalizeForComparison(String value) {
        if (value == null) {
            return "";
        }

        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }
}