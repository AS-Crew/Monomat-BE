package io.github.ascrew.monomatbe.domain.lobby.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 빈 로비 reaper Prometheus 메트릭.
 *
 * [목적]
 * 유령 로비 정리는 장애 복구성 로직이므로 처리량·폭파 건수·실패를 분리 계측해
 * 운영 중 reaper 동작과 실패율을 관측할 수 있게 한다.
 *
 * [노출 메트릭] (/actuator/prometheus 기준 _total 접미사)
 * - lobby_reaper_scanned_total : 검사한 로비 후보 수
 * - lobby_reaper_reaped_total  : 실제 폭파한 로비 수
 * - lobby_reaper_error_total   : reaper 처리 실패(ERROR 결과) 수
 *
 * WebSocketMetric과 동일한 Micrometer 컴포넌트 패턴을 따른다.
 */
@Component
public class LobbyReaperMetric {

    private final Counter scannedCounter;
    private final Counter reapedCounter;
    private final Counter errorCounter;

    public LobbyReaperMetric(MeterRegistry meterRegistry) {
        this.scannedCounter = Counter.builder("lobby.reaper.scanned")
                .description("빈 로비 reaper가 검사한 로비 후보 수")
                .register(meterRegistry);
        this.reapedCounter = Counter.builder("lobby.reaper.reaped")
                .description("빈 로비 reaper가 폭파한 로비 수")
                .register(meterRegistry);
        this.errorCounter = Counter.builder("lobby.reaper.error")
                .description("빈 로비 reaper 처리 실패 수")
                .register(meterRegistry);
    }

    public void incrementScanned(long count) {
        if (count > 0) {
            scannedCounter.increment(count);
        }
    }

    public void incrementReaped() {
        reapedCounter.increment();
    }

    public void incrementError() {
        errorCounter.increment();
    }
}
