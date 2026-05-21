package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 공개 로비 Redis 인덱스 정합성 보정 스케줄러.
 *
 * [배경]
 * 공개 로비 목록 조회는 lobby:public Set과 여러 ZSET 정렬 인덱스를 사용한다.
 * 정상 로비 삭제/폭파 경로에서는 Lua가 인덱스를 함께 제거한다.
 * 하지만 Redis 수동 조작, 과거 버전 로직, 예외 상황으로 인해
 * 인덱스에는 code가 남아 있는데 lobby:{code} Hash가 없는 stale index가 생길 수 있다.
 *
 * [정리 전략]
 * 조회 API 요청 중 즉시 삭제하지 않는다.
 * 조회 경로는 읽기 책임만 갖고, stale 정리는 이 스케줄러가 제한된 batch 단위로 수행한다.
 *
 * [주의]
 * 이 스캐너는 실시간 정확성 보장용이 아니라 운영 중 누적되는 stale index를 점진적으로 줄이는 보정 루틴이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyPublicIndexCleanupScheduler {

    /**
     * 한 번의 스캔에서 확인할 최대 공개 로비 코드 수.
     *
     * 너무 크게 잡으면 Redis 부하가 커질 수 있고,
     * 너무 작게 잡으면 stale 회복 속도가 느려진다.
     * 현재 서비스 규모에서는 100개 단위 점진 정리가 적절하다.
     */
    private static final int CLEANUP_SCAN_BATCH_SIZE = 100;

    private final LobbyRepository lobbyRepository;

    /**
     * 공개 로비 인덱스에서 stale code를 주기적으로 정리한다.
     *
     * fixedDelay 기준:
     * - 이전 실행이 끝난 뒤 60초 후 다시 실행한다.
     * - Redis 또는 서버 부하가 순간적으로 증가해도 중첩 실행되지 않는다.
     */
    @Scheduled(fixedDelayString = "${monomat.lobby.public-index-cleanup.fixed-delay-ms:60000}")
    public void cleanupStalePublicLobbyIndexes() {
        List<String> candidateLobbyCodes =
                lobbyRepository.getPublicLobbyCodesForCleanup(CLEANUP_SCAN_BATCH_SIZE);

        if (candidateLobbyCodes.isEmpty()) {
            return;
        }

        int removedCount = 0;

        for (String lobbyCode : candidateLobbyCodes) {
            if (lobbyCode == null || lobbyCode.isBlank()) {
                continue;
            }

            if (lobbyRepository.existsByCode(lobbyCode)) {
                continue;
            }

            lobbyRepository.removePublicLobbyIndexes(lobbyCode);
            removedCount++;
        }

        if (removedCount > 0) {
            log.warn(
                    "공개 로비 stale index 배치 정리 완료 - scanned: {}, removed: {}",
                    candidateLobbyCodes.size(),
                    removedCount
            );
        }
    }
}