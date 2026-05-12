package io.github.ascrew.monomatbe.domain.lobby.service;

import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 게임 시작 상태 동기화 실패 재처리 서비스
 *
 * [처리 대상]
 * Redis start_lobby.lua는 성공했지만 DB GAME_LOBBY 상태 변경 또는
 * Redis 보상 롤백이 실패하여 Redis-DB 상태가 불일치할 가능성이 있는 로비를 보정한다.
 *
 * [현재 보정 정책]
 * START_DB_SYNC_FAILED 사유는 사용자가 /start 요청에서 500을 받은 상황
 * GAME_STARTED 이벤트도 afterCommit 이전에 발행되지 않았으므로,
 * Redis 상태를 DB 기준 WAITING 상태로 되돌리는 것을 우선한다.
 */
@Slf4j
@Service
@Profile("!test")
@RequiredArgsConstructor
public class LobbyStartReconciliationService {

    private static final String LOG_ALERT_REQUIRED = "[ALERT_REQUIRED]";
    private static final String PAYLOAD_DELIMITER = "\\|";
    private static final int EXPECTED_PAYLOAD_PARTS = 2;

    private final LobbyRepository lobbyRepository;

    /**
     * Redis-DB 게임 시작 상태 불일치 재처리 큐를 주기적으로 처리한다.
     *
     * fixedDelayString은 환경별 조정을 위해 설정값을 사용할 수 있게 둔다.
     * 기본값은 30초
     */
    @Scheduled(fixedDelayString = "${monomat.lobby.start-reconciliation.fixed-delay-ms:30000}")
    public void reconcileStartState() {
        String payload = lobbyRepository.pollStartReconciliation();

        if (!StringUtils.hasText(payload)) {
            return;
        }

        ReconciliationPayload parsedPayload = parsePayload(payload);

        if (parsedPayload == null) {
            log.error(
                    "{} 게임 시작 상태 재처리 payload 파싱 실패 - payload: {}",
                    LOG_ALERT_REQUIRED,
                    payload
            );
            lobbyRepository.incrementStartReconciliationMetric(
                    RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED
            );
            return;
        }

        boolean reconciled = lobbyRepository.rollbackStartedLobbyStatus(parsedPayload.lobbyCode());

        if (reconciled) {
            lobbyRepository.incrementStartReconciliationMetric(
                    RedisKeys.METRIC_LOBBY_START_RECONCILIATION_SUCCESS
            );

            log.warn(
                    "게임 시작 상태 재처리 완료 - lobbyCode: {}, reason: {}",
                    parsedPayload.lobbyCode(),
                    parsedPayload.reason()
            );
            return;
        }

        lobbyRepository.requeueStartReconciliation(payload);
        lobbyRepository.incrementStartReconciliationMetric(
                RedisKeys.METRIC_LOBBY_START_RECONCILIATION_FAILED
        );

        log.error(
                "{} 게임 시작 상태 재처리 실패 - payload 재적재 완료. lobbyCode: {}, reason: {}",
                LOG_ALERT_REQUIRED,
                parsedPayload.lobbyCode(),
                parsedPayload.reason()
        );
    }

    /**
     * Redis 큐 payload를 파싱
     * payload 형식 : lobbyCode|reason
     */
    private ReconciliationPayload parsePayload(String payload) {
        String[] parts = payload.split(PAYLOAD_DELIMITER, EXPECTED_PAYLOAD_PARTS);

        if (parts.length != EXPECTED_PAYLOAD_PARTS
                || !StringUtils.hasText(parts[0])
                || !StringUtils.hasText(parts[1])) {
            return null;
        }

        return new ReconciliationPayload(parts[0], parts[1]);
    }

    private record ReconciliationPayload(
            String lobbyCode,
            String reason
    ) {
    }
}