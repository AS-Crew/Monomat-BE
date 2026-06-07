package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.ReapLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.event.LobbyClosedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 빈 로비 자동 폭파(reaper) 스케줄러.
 *
 * [배경]
 * 로비를 제거하는 정상 경로는 leave_lobby.lua의 SCARD==0 -> DESTROYED 하나뿐이며,
 * 이는 모든 참여자가 1명씩 정상 STOMP DISCONNECT로 제거되는 것에 전적으로 의존한다.
 * 비정상 종료(브라우저 강제 종료, 네트워크 단절, 서버 크래시, ws:connection TTL 만료)로
 * DISCONNECT가 누락되거나, 생성 직후 아무도 구독하지 않은 로비는
 * participants Set에 유령으로 남거나 0명인 채로 영구 잔존한다.
 *
 * [정리 전략]
 * 전체 로비 인덱스(lobby:all)를 제한된 batch 단위로 순회하며 코드별로 reap_lobby.lua를
 * 실행한다. "활성 세션 0"(0명 또는 전원 오프라인) 로비만 leave_lobby의 DESTROYED 경로와
 * 동일하게 폭파한다. 온라인 판정·grace 보호·폭파가 모두 Lua 한 스크립트 안에서 원자적으로
 * 수행되므로 스캔과 폭파 사이의 join race(TOCTOU)가 없다.
 *
 * [실시간 경로와의 관계]
 * 정상 DISCONNECT 경로(LobbyLeaveEventHandler)가 실시간 1차 안전망이고,
 * 이 스케줄러는 DISCONNECT 누락을 복구하는 2차 안전망이다.
 *
 * [단일 인스턴스 운영]
 * reap_lobby.lua가 원자적·멱등이므로 별도 분산 락 없이도 안전하다.
 * 다중 인스턴스 동시 실행 시에도 첫 폭파 이후는 STALE_INDEX로 수렴한다.
 */
@Slf4j
@Component
public class LobbyReaperScheduler {

    /**
     * 한 번의 스캔에서 검사할 최대 로비 코드 수.
     * LobbyPublicIndexCleanupScheduler와 동일한 점진 정리 정책을 따른다.
     */
    private static final int REAP_SCAN_BATCH_SIZE = 100;

    private final LobbyRepository lobbyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final LobbyRealtimeNotifier lobbyRealtimeNotifier;
    private final LobbyReaperMetric lobbyReaperMetric;

    /**
     * 생성 직후 폭파를 보류할 grace 기간(ms).
     *
     * [필요 이유]
     * 로비는 REST 생성 직후 participants가 0이며, 실제 등록은 WebSocket 구독 시점에 일어난다.
     * 이 정상 윈도우를 보호하려면 grace가 생성->구독 갭보다 충분히 커야 한다.
     */
    private final long graceMillis;

    public LobbyReaperScheduler(
            LobbyRepository lobbyRepository,
            ApplicationEventPublisher eventPublisher,
            LobbyRealtimeNotifier lobbyRealtimeNotifier,
            LobbyReaperMetric lobbyReaperMetric,
            @Value("${monomat.lobby.reaper.grace-ms:120000}") long graceMillis
    ) {
        this.lobbyRepository = lobbyRepository;
        this.eventPublisher = eventPublisher;
        this.lobbyRealtimeNotifier = lobbyRealtimeNotifier;
        this.lobbyReaperMetric = lobbyReaperMetric;
        this.graceMillis = graceMillis;
    }

    /**
     * 활성 세션이 0인 빈 로비를 주기적으로 폭파한다.
     *
     * fixedDelay 기준:
     * - 이전 실행이 끝난 뒤 지정 시간 후 다시 실행한다.
     * - Redis 또는 서버 부하가 순간적으로 증가해도 중첩 실행되지 않는다.
     */
    @Scheduled(fixedDelayString = "${monomat.lobby.reaper.fixed-delay-ms:60000}")
    public void reapEmptyLobbies() {
        List<String> candidateLobbyCodes =
                lobbyRepository.getAllLobbyCodesForReaping(REAP_SCAN_BATCH_SIZE);

        if (candidateLobbyCodes.isEmpty()) {
            return;
        }

        int scannedCount = 0;
        int reapedCount = 0;

        for (String lobbyCode : candidateLobbyCodes) {
            if (lobbyCode == null || lobbyCode.isBlank()) {
                continue;
            }

            scannedCount++;
            ReapLobbyResult result = lobbyRepository.reapEmptyLobby(lobbyCode, graceMillis);

            switch (result) {
                case REAPED -> {
                    /*
                     * 로비별 LobbyClosedEvent는 game 도메인이 orphan 게임 세션 키를
                     * 정리하도록 위임하는 신호이므로 폭파된 로비마다 발행한다.
                     * (공개 목록 refresh는 배치 종료 후 1회만 보낸다.)
                     */
                    eventPublisher.publishEvent(new LobbyClosedEvent(lobbyCode));
                    lobbyReaperMetric.incrementReaped();
                    reapedCount++;
                }
                case ERROR -> {
                    log.warn("빈 로비 reaper 처리 실패 - lobbyCode: {}", lobbyCode);
                    lobbyReaperMetric.incrementError();
                }
                case ALIVE, TOO_YOUNG, STALE_INDEX -> {
                    // 정상 비폭파 결과 - 별도 처리 없음
                }
            }
        }

        lobbyReaperMetric.incrementScanned(scannedCount);

        if (reapedCount > 0) {
            /*
             * 한 배치에서 N개를 폭파해도 "목록 다시 조회" 신호는 동일하므로,
             * FE의 중복 refetch를 막기 위해 배치 종료 후 1회만 브로드캐스트한다.
             * 브로드캐스트 실패가 폭파 처리에 영향을 주지 않도록 try-catch로 격리한다.
             */
            try {
                lobbyRealtimeNotifier.notifyLobbyListRefresh();
            } catch (Exception e) {
                log.warn("빈 로비 reaper 목록 갱신 알림 실패 - reaped: {}", reapedCount, e);
            }

            log.warn(
                    "빈 로비 reaper 배치 폭파 완료 - scanned: {}, reaped: {}",
                    scannedCount,
                    reapedCount
            );
        }
    }
}
