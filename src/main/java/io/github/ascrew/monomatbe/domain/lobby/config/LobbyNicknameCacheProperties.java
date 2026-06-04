package io.github.ascrew.monomatbe.domain.lobby.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 로비 방장 닉네임 캐시 정책 설정
 *
 * 로비 목록(GET /api/lobbies)은 호출 빈도가 높아 매 요청마다 닉네임을 DB로 조회하면
 * latency spike와 DB 커넥션 병목으로 증폭될 수 있다. userIdentifier 기준 닉네임을
 * 짧은 TTL로 캐싱하므로, 그 TTL을 설정값으로 관리한다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "monomat.lobby.nickname-cache")
public class LobbyNicknameCacheProperties {

    /**
     * 닉네임 캐시 TTL.
     *
     * 짧게 둘수록 닉네임 변경이 빠르게 반영되지만 DB 조회 빈도가 늘어난다.
     * 닉네임 변경 즉시성이 요구되지 않으므로 분 단위의 짧은 TTL을 기본값으로 둔다.
     */
    @NotNull
    private Duration ttl = Duration.ofMinutes(10);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
