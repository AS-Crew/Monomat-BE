package io.github.ascrew.monomatbe.domain.report.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 신고 정책 설정
 *
 * [설계 의도]
 * 신고 임계값과 자동 비공개 전환 여부는 운영 정책에 해당한다.
 * 코드에 숫자를 하드코딩하지 않고 설정값으로 분리해,
 * 운영 환경에서 환경변수만으로 정책을 조정할 수 있도록 한다.
 *
 * [현재 정책]
 * - lobbyReviewThreshold:
 *   특정 로비의 PENDING 신고 수가 이 값 이상이면 관리자 검토 대상이다.
 *
 * - autoPrivateEnabled:
 *   true이면 추후 신고 임계값 초과 시 자동 비공개 전환을 수행할 수 있다.
 *   현재 단계에서는 실제 자동 비공개 전환은 수행하지 않고 정책 판단 결과만 제공한다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "report.policy")
public class ReportPolicyProperties {

    /**
     * 로비 관리자 검토 임계값
     *
     * 예:
     * 5로 설정하면 특정 로비의 PENDING 신고가 5건 이상일 때
     * 관리자 검토 대상이 된다.
     */
    @Min(value = 1, message = "로비 신고 검토 임계값은 1 이상이어야 합니다.")
    private int lobbyReviewThreshold = 5;

    /**
     * 신고 임계값 초과 시 자동 비공개 전환을 허용할지 여부
     *
     * 현재 이슈에서는 실제 자동 비공개 전환을 수행하지 않고,
     * 후속 이슈에서 이 값을 기준으로 정책을 연결할 수 있도록 한다.
     */
    private boolean autoPrivateEnabled = false;

    public int getLobbyReviewThreshold() {
        return lobbyReviewThreshold;
    }

    public void setLobbyReviewThreshold(int lobbyReviewThreshold) {
        this.lobbyReviewThreshold = lobbyReviewThreshold;
    }

    public boolean isAutoPrivateEnabled() {
        return autoPrivateEnabled;
    }

    public void setAutoPrivateEnabled(boolean autoPrivateEnabled) {
        this.autoPrivateEnabled = autoPrivateEnabled;
    }
}