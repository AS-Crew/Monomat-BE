package io.github.ascrew.monomatbe.global.websocket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebSocketMetric {
    // 동시성 이슈 때문에 아토믹인티저 사용
    private final AtomicInteger activeSessions = new AtomicInteger(0);

    public WebSocketMetric(MeterRegistry meterRegistry) {
        //
        Gauge.builder("websocketActiveSessions", activeSessions, AtomicInteger::get)
                .description("웹소켓 현재 활성화된 세션 수")
                .register(meterRegistry);
    }

    public void increment() {
        // 증가
        activeSessions.incrementAndGet();
    }

    public void decrement() {
        // 감소 0과 v-1과 비교해서 더 큰값 반환 0밑으로 떨어지지마라
        activeSessions.updateAndGet(v->Math.max(0, v-1));
    }
}
