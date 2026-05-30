package io.github.ascrew.monomatbe.domain.chat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 로비 최근 채팅 보관 정책 설정
 *
 * 최근 채팅은 영구 저장 목적이 아니라 새로고침/늦은 입장 UX 보조 목적이므로,
 * 보관 개수와 TTL을 설정값으로 관리한다.
 */
@Validated
@Component
@ConfigurationProperties(prefix = "chat.lobby.recent-messages")
public class LobbyRecentChatProperties {

    /**
     * 로비별 Redis List에 보관할 최근 채팅 최대 개수
     */
    @Min(1)
    @Max(200)
    private int maxSize = 50;

    /**
     * 로비 최근 채팅 Redis List TTL
     */
    @NotNull
    private Duration ttl = Duration.ofHours(2);

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}