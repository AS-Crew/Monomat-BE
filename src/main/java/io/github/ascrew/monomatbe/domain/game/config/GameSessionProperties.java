package io.github.ascrew.monomatbe.domain.game.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 게임 세션 정책 설정.
 *
 * 새 게임 시작 시 동일 로비에 미종료(active) 세션이 남아 있으면 기본적으로 차단하되,
 * 비정상 종료 등으로 정체된 세션은 복구(강제 종료 후 재시작 허용)해야 한다.
 * 정체 판별 임계 기간을 설정값으로 관리한다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "monomat.game.session")
public class GameSessionProperties {

    /**
     * active 세션을 stale(정체)로 간주하는 임계 기간.
     *
     * {@code started_at}으로부터 이 기간이 지난 미종료 세션은 비정상 종료로 정체된 것으로 보고
     * 새 게임 시작 시 복구 대상으로 삼는다. 정상 게임 1판 최대 진행 시간보다 충분히 길게 둔다.
     */
    @NotNull
    private Duration staleThreshold = Duration.ofMinutes(30);

    public Duration getStaleThreshold() {
        return staleThreshold;
    }

    public void setStaleThreshold(Duration staleThreshold) {
        this.staleThreshold = staleThreshold;
    }
}
