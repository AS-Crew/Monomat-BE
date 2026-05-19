package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.StartLobbyLuaResultCode;
import io.github.ascrew.monomatbe.domain.lobby.StartLobbyResult;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 로비 Lua script 반환 문자열을 도메인 결과 타입으로 변환하는 Mapper
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LobbyLuaResultMapper {

    // =========================================================
    // leave_lobby.lua 반환값 상수
    // =========================================================

    private static final String RESULT_DESTROYED = "DESTROYED";
    private static final String RESULT_LEFT = "LEFT";
    private static final String RESULT_DELEGATED_PREFIX = "DELEGATED:";
    private static final String RESULT_INVALID_LOBBY_CAPACITY = "INVALID_LOBBY_CAPACITY";

    // =========================================================
    // kick_lobby.lua 반환값 상수
    // =========================================================

    private static final String RESULT_KICKED_PREFIX = "KICKED:";
    private static final String RESULT_LOBBY_NOT_FOUND = "LOBBY_NOT_FOUND";
    private static final String RESULT_HOST_NOT_FOUND = "HOST_NOT_FOUND";
    private static final String RESULT_FORBIDDEN = "FORBIDDEN";
    private static final String RESULT_CANNOT_KICK_SELF = "CANNOT_KICK_SELF";
    private static final String RESULT_TARGET_NOT_PARTICIPANT = "TARGET_NOT_PARTICIPANT";

    /**
     * 운영 확인이 필요한 Redis 정합성 문제 로그 식별자
     */
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

    private final StringRedisTemplate redisTemplate;

    /**
     * leave_lobby.lua 반환 문자열을 LeaveLobbyResult로 변환한다.
     *
     * @param result Lua 반환 문자열
     * @param code 로비 초대 코드
     * @param userId 퇴장 요청 사용자 식별자
     * @return 도메인 퇴장 결과
     */
    public LeaveLobbyResult toLeaveLobbyResult(String result, String code, String userId) {
        if (result == null) {
            return new LeaveLobbyResult.Error("Lua 스크립트 반환값이 null입니다.");
        }

        if (RESULT_DESTROYED.equals(result)) {
            return new LeaveLobbyResult.Destroyed(code);
        }

        if (RESULT_LEFT.equals(result)) {
            return new LeaveLobbyResult.Left(code, userId);
        }

        if (result.startsWith(RESULT_DELEGATED_PREFIX)) {
            String newHostId = result.substring(RESULT_DELEGATED_PREFIX.length());
            return new LeaveLobbyResult.Delegated(code, newHostId);
        }

        if (RESULT_INVALID_LOBBY_CAPACITY.equals(result)) {
            log.error(
                    "{} leave_lobby.lua Redis 로비 capacity 필드 손상 감지 - lobbyCode: {}, userId: {}, result: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    userId,
                    result
            );

            return new LeaveLobbyResult.Error(
                    "Redis 로비 capacity 필드가 유효하지 않습니다. lobbyCode=" + code
            );
        }

        return new LeaveLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
    }

    /**
     * kick_lobby.lua 반환 문자열을 KickLobbyResult로 변환한다.
     *
     * @param result Lua 반환 문자열
     * @param code 로비 초대 코드
     * @param requesterIdentifier 강퇴 요청자 식별자
     * @param targetUserIdentifier 강퇴 대상 식별자
     * @return 도메인 강퇴 결과
     */
    public KickLobbyResult toKickLobbyResult(
            String result,
            String code,
            String requesterIdentifier,
            String targetUserIdentifier
    ) {
        if (result == null) {
            return new KickLobbyResult.Error("Lua 스크립트 반환값이 null입니다.");
        }

        if (result.startsWith(RESULT_KICKED_PREFIX)) {
            String targetWsSessionId = result.substring(RESULT_KICKED_PREFIX.length());

            return new KickLobbyResult.Kicked(
                    code,
                    targetUserIdentifier,
                    targetWsSessionId
            );
        }

        if (RESULT_LOBBY_NOT_FOUND.equals(result)) {
            return new KickLobbyResult.LobbyNotFound(code);
        }

        if (RESULT_HOST_NOT_FOUND.equals(result)) {
            return new KickLobbyResult.HostNotFound(code);
        }

        if (RESULT_FORBIDDEN.equals(result)) {
            return new KickLobbyResult.Forbidden(code, requesterIdentifier);
        }

        if (RESULT_CANNOT_KICK_SELF.equals(result)) {
            return new KickLobbyResult.CannotKickSelf(code, requesterIdentifier);
        }

        if (RESULT_TARGET_NOT_PARTICIPANT.equals(result)) {
            return new KickLobbyResult.TargetNotParticipant(code, targetUserIdentifier);
        }

        return new KickLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
    }

    /**
     * start_lobby.lua 반환 문자열을 StartLobbyResult로 변환한다.
     *
     * [주의]
     * start 결과는 단순 매핑 외에 ready/participants 정합성 진단 로그와 metric 증가가 필요하다.
     * 기존 LobbyRepositoryImpl에 있던 관측성 동작을 유지하기 위해 이 Mapper에서 동일하게 처리한다.
     *
     * @param result Lua 반환 문자열
     * @param code 로비 초대 코드
     * @param requesterIdentifier 게임 시작 요청자 식별자
     * @return 도메인 게임 시작 결과
     */
    public StartLobbyResult toStartLobbyResult(
            String result,
            String code,
            String requesterIdentifier
    ) {
        if (result == null) {
            incrementMetric(RedisKeys.METRIC_START_LOBBY_UNKNOWN_RESULT);

            log.error(
                    "{} start_lobby.lua null 반환값 - lobbyCode: {}, requesterIdentifier: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    requesterIdentifier
            );

            return new StartLobbyResult.Error("Lua 스크립트 반환값이 null입니다.");
        }

        if (StartLobbyLuaResultCode.isNotReadyResult(result)) {
            String notReadyUserIdentifier =
                    StartLobbyLuaResultCode.extractNotReadyUserIdentifier(result);

            logReadyConsistencyFailure(
                    code,
                    requesterIdentifier,
                    notReadyUserIdentifier
            );

            return new StartLobbyResult.NotReady(code, notReadyUserIdentifier);
        }

        if (StartLobbyLuaResultCode.isStaleParticipantResult(result)) {
            String staleParticipantUserIdentifier =
                    StartLobbyLuaResultCode.extractStaleParticipantUserIdentifier(result);

            logReadyConsistencyFailure(
                    code,
                    requesterIdentifier,
                    staleParticipantUserIdentifier
            );

            log.error(
                    "{} 게임 시작 실패 - stale participant 감지. lobbyCode: {}, requester: {}, staleParticipant: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    requesterIdentifier,
                    staleParticipantUserIdentifier
            );

            incrementMetric(RedisKeys.METRIC_LOBBY_READY_CONSISTENCY_FAILURE);

            return new StartLobbyResult.StaleParticipant(code, staleParticipantUserIdentifier);
        }

        return StartLobbyLuaResultCode.fromExactValue(result)
                .map(resultCode -> toExactStartLobbyResult(resultCode, code, requesterIdentifier))
                .orElseGet(() -> {
                    incrementMetric(RedisKeys.METRIC_START_LOBBY_UNKNOWN_RESULT);

                    log.error(
                            "{} start_lobby.lua 알 수 없는 반환값 - lobbyCode: {}, requesterIdentifier: {}, result: {}",
                            LOG_MONITORING_REQUIRED,
                            code,
                            requesterIdentifier,
                            result
                    );

                    return new StartLobbyResult.Error("알 수 없는 Lua 반환값: " + result);
                });
    }

    /**
     * start_lobby.lua exact 반환 코드를 StartLobbyResult로 변환한다.
     *
     * NOT_READY_PREFIX, STALE_PARTICIPANT_PREFIX는 동적 prefix 결과이므로
     * 이 메서드의 exact 매핑 대상이 아니다.
     */
    private StartLobbyResult toExactStartLobbyResult(
            StartLobbyLuaResultCode resultCode,
            String code,
            String requesterIdentifier
    ) {
        return switch (resultCode) {
            case STARTED -> new StartLobbyResult.Started(code);
            case LOBBY_NOT_FOUND -> new StartLobbyResult.LobbyNotFound(code);
            case HOST_NOT_FOUND -> new StartLobbyResult.HostNotFound(code);
            case FORBIDDEN -> new StartLobbyResult.Forbidden(code, requesterIdentifier);
            case LOBBY_NOT_WAITING -> new StartLobbyResult.LobbyNotWaiting(code);
            case MAP_NOT_SELECTED -> new StartLobbyResult.MapNotSelected(code);
            case NO_PLAYER -> new StartLobbyResult.NoPlayer(code);
            case NOT_READY_PREFIX -> new StartLobbyResult.Error(
                    "NOT_READY_PREFIX는 동적 prefix 결과이므로 exact 매핑 대상이 아닙니다."
            );
            case STALE_PARTICIPANT_PREFIX -> new StartLobbyResult.Error(
                    "STALE_PARTICIPANT_PREFIX는 동적 prefix 결과이므로 exact 매핑 대상이 아닙니다."
            );
        };
    }

    /**
     * start_lobby.lua가 NOT_READY 또는 STALE_PARTICIPANT를 반환했을 때
     * ready/participants 정합성 진단 로그를 남긴다.
     *
     * [목적]
     * 단순히 "준비 안 됨"으로만 남기면 실제 미준비 유저인지,
     * 퇴장/강퇴 후 participants Set에 남은 stale 유저인지 추적하기 어렵다.
     */
    private void logReadyConsistencyFailure(
            String code,
            String requesterIdentifier,
            String notReadyUserIdentifier
    ) {
        String participantsKey = RedisKeys.lobbyParticipantsKey(code);
        String readyKey = RedisKeys.lobbyReadyKey(code);
        String lobbyUserSessionKey = RedisKeys.lobbyUserSessionKey(code, notReadyUserIdentifier);

        try {
            Long participantCount = redisTemplate.opsForSet().size(participantsKey);
            Long readyCount = redisTemplate.opsForSet().size(readyKey);

            Boolean isParticipant = redisTemplate.opsForSet()
                    .isMember(participantsKey, notReadyUserIdentifier);

            Boolean isReady = redisTemplate.opsForSet()
                    .isMember(readyKey, notReadyUserIdentifier);

            boolean hasActiveLobbySession = Boolean.TRUE.equals(
                    redisTemplate.hasKey(lobbyUserSessionKey)
            );

            Set<String> participants = redisTemplate.opsForSet().members(participantsKey);
            Set<String> readyParticipants = redisTemplate.opsForSet().members(readyKey);

            Set<String> staleReadyParticipants = new HashSet<>(
                    readyParticipants != null ? readyParticipants : Set.of()
            );
            staleReadyParticipants.removeAll(participants != null ? participants : Set.of());

            incrementMetric(RedisKeys.METRIC_LOBBY_READY_CONSISTENCY_FAILURE);

            log.warn(
                    "{} 게임 시작 실패 READY 정합성 진단 - lobbyCode: {}, requester: {}, "
                            + "notReadyUserIdentifier: {}, participantCount: {}, readyCount: {}, "
                            + "isParticipant: {}, isReady: {}, hasActiveLobbySession: {}, "
                            + "staleReadyCount: {}, staleReadyParticipants: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    requesterIdentifier,
                    notReadyUserIdentifier,
                    participantCount,
                    readyCount,
                    isParticipant,
                    isReady,
                    hasActiveLobbySession,
                    staleReadyParticipants.size(),
                    staleReadyParticipants
            );

        } catch (Exception e) {
            log.error(
                    "{} 게임 시작 실패 READY 정합성 진단 로그 생성 실패 - lobbyCode: {}, requester: {}, "
                            + "notReadyUserIdentifier: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    requesterIdentifier,
                    notReadyUserIdentifier,
                    e
            );
        }
    }

    /**
     * Redis metric counter를 증가시킨다.
     *
     * [정책]
     * metric 증가 실패가 실제 로비 흐름을 실패시키면 안 된다.
     * 따라서 실패 시 warn 로그만 남기고 흐름은 유지한다.
     */
    private void incrementMetric(String metricKey) {
        try {
            redisTemplate.opsForValue().increment(metricKey);
        } catch (Exception e) {
            log.warn("로비 Lua 결과 metric 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}