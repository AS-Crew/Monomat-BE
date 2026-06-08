package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.KickLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.LeaveLobbyResult;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 로비 Redis command 전용 Repository
 *
 * [담당 범위]
 * - ready 상태 변경
 * - Redis 로비 데이터 보상 삭제
 * - 게임 시작 실패 시 Redis 상태 보상 롤백
 * - 퇴장/강퇴 후 ready Set 정리
 * - 게임 시작 전 stale ready 데이터 정리
 * - 로비 맵 메타데이터 갱신
 * - 로비 설정값 갱신
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRedisCommandRepository {

    /**
     * 운영 확인이 필요한 Redis 정리 실패 로그 식별자
     */
    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

    /** Redis Hash에 저장되는 공개 로비 is_private 값 */
    private static final String IS_PRIVATE_FALSE = "false";

    /** Redis Hash에 저장되는 비공개 로비 is_private 값 */
    private static final String IS_PRIVATE_TRUE = "true";

    private final StringRedisTemplate redisTemplate;

    /**
     * 로비 참여자의 ready 상태를 변경한다.
     *
     * [정책]
     * - ready=true  → lobby:{code}:ready Set에 사용자 추가
     * - ready=false → lobby:{code}:ready Set에서 사용자 제거
     *
     * @param code 로비 초대 코드
     * @param userIdentifier 사용자 식별자
     * @param ready 변경할 ready 상태
     */
    public void updateReadyStatus(String code, String userIdentifier, boolean ready) {
        String readyKey = RedisKeys.lobbyReadyKey(code);

        if (ready) {
            redisTemplate.opsForSet().add(readyKey, userIdentifier);
            return;
        }

        redisTemplate.opsForSet().remove(readyKey, userIdentifier);
    }

    /**
     * DB Insert 실패 시 Redis에 저장된 로비 데이터를 보상 삭제한다.
     *
     * [삭제 대상]
     * - lobby:{code}
     * - lobby:{code}:participants
     * - lobby:{code}:order
     * - lobby:{code}:kicked
     * - lobby:{code}:ready
     * - lobby:{code}:lock
     * - lobby:public Set의 code
     *
     * [삭제 실패 처리]
     * 보상 삭제 실패 시 ERROR 로그를 남기고 false를 반환한다.
     * 서비스 레이어에서 반환값을 확인하여 추가 알림 처리가 가능하다.
     *
     * @param inviteCode 삭제할 로비 초대 코드
     * @return 보상 삭제 성공 여부
     */
    public boolean deleteFromRedis(String inviteCode) {
        List<String> keysToDelete = List.of(
                RedisKeys.lobbyKey(inviteCode),
                RedisKeys.lobbyParticipantsKey(inviteCode),
                RedisKeys.lobbyOrderKey(inviteCode),
                RedisKeys.lobbyKickedKey(inviteCode),
                RedisKeys.lobbyReadyKey(inviteCode),
                RedisKeys.lobbyCodeLockKey(inviteCode)
        );

        try {
            redisTemplate.delete(keysToDelete);
            redisTemplate.opsForSet().remove(RedisKeys.LOBBY_PUBLIC, inviteCode);
            redisTemplate.opsForSet().remove(RedisKeys.LOBBY_ALL, inviteCode);

            log.info("Redis 보상 삭제 완료 - code: {}, keys: {}", inviteCode, keysToDelete);
            return true;

        } catch (Exception e) {
            log.error(
                    "{} Redis 보상 삭제 실패 - code: {}, keys: {}, publicLobbyKey: {}. "
                            + "로비 잔여 데이터가 조회/ready/canStart 계산에 영향을 줄 수 있으므로 수동 정리 또는 재처리가 필요합니다.",
                    LOG_MONITORING_REQUIRED,
                    inviteCode,
                    keysToDelete,
                    RedisKeys.LOBBY_PUBLIC,
                    e
            );
            return false;
        }
    }

    /**
     * Redis 로비 상태를 WAITING으로 보상 롤백한다.
     *
     * [필요 이유]
     * 게임 시작 Lua가 성공하면 Redis 로비 상태는 PLAYING으로 바뀌고, 공개 로비 목록(lobby:public)에서도 제거된다.
     * 이후 DB GAME_LOBBY 상태 변경이 실패하면 Redis는 PLAYING, DB는 WAITING인
     * 불일치 상태가 될 수 있으므로 가능한 범위에서 Redis 상태를 되돌린다.
     *
     * [보상 범위]
     * - lobby:{code}.status = WAITING
     * - 확실히 공개 로비인 경우 lobby:public에 code 재등록
     *
     * [보안 정책]
     * is_private 값이 null/blank/unknown이면 public 복구를 하지 않는다.
     * 확실히 public일 때만 공개 로비 Set에 복구해야 private 로비 노출 위험을 줄일 수 있다.
     *
     * @param code 로비 초대 코드
     * @return Redis 보상 롤백 성공 여부
     */
    public boolean rollbackStartedLobbyStatus(String code) {
        String lobbyKey = RedisKeys.lobbyKey(code);

        try {
            Map<Object, Object> lobbyData = redisTemplate.opsForHash().entries(lobbyKey);

            if (lobbyData.isEmpty()) {
                log.error(
                        "{} 게임 시작 Redis 보상 롤백 실패 - 로비 데이터 없음. code: {}, lobbyKey: {}",
                        LOG_MONITORING_REQUIRED,
                        code,
                        lobbyKey
                );
                return false;
            }

            redisTemplate.opsForHash().put(
                    lobbyKey,
                    RedisKeys.FIELD_STATUS,
                    LobbyStatus.WAITING.name()
            );

            String rawIsPrivate = (String) lobbyData.get(RedisKeys.FIELD_IS_PRIVATE);
            boolean restoredPublic = restorePublicLobbyIfClearlyPublic(
                    code,
                    lobbyKey,
                    rawIsPrivate
            );

            log.warn(
                    "게임 시작 Redis 보상 롤백 완료 - code: {}, status: {}, restoredPublic: {}, rawIsPrivate: {}",
                    code,
                    LobbyStatus.WAITING.name(),
                    restoredPublic,
                    rawIsPrivate
            );

            return true;

        } catch (Exception e) {
            log.error(
                    "{} 게임 시작 Redis 보상 롤백 실패 - code: {}, lobbyKey: {}. "
                            + "Redis는 PLAYING인데 DB는 WAITING일 수 있으므로 재처리 큐 확인이 필요합니다.",
                    LOG_MONITORING_REQUIRED,
                    code,
                    lobbyKey,
                    e
            );
            return false;
        }
    }

    /**
     * 확실히 공개 로비인 경우에만 lobby:public Set에 복구한다.
     */
    private boolean restorePublicLobbyIfClearlyPublic(
            String code,
            String lobbyKey,
            String rawIsPrivate
    ) {
        if (rawIsPrivate == null || rawIsPrivate.isBlank()) {
            log.error(
                    "{} 게임 시작 Redis 보상 롤백 중 is_private 필드 누락 - public 복구 생략. "
                            + "code: {}, lobbyKey: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    lobbyKey
            );
            return false;
        }

        if (IS_PRIVATE_FALSE.equals(rawIsPrivate)) {
            redisTemplate.opsForSet().add(RedisKeys.LOBBY_PUBLIC, code);
            return true;
        }

        if (!IS_PRIVATE_TRUE.equals(rawIsPrivate)) {
            log.error(
                    "{} 게임 시작 Redis 보상 롤백 중 알 수 없는 is_private 값 - public 복구 생략. "
                            + "code: {}, lobbyKey: {}, rawIsPrivate: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    lobbyKey,
                    rawIsPrivate
            );
        }

        return false;
    }

    /**
     * 퇴장 처리 결과에 따라 ready Set을 정리한다.
     *
     * [필요 이유]
     * 참여자가 로비를 나가면 더 이상 준비 상태에 포함되면 안 된다.
     *
     * [Destroyed를 다루지 않는 이유]
     * 로비 폭파 시 ready Set 전체 삭제는 leave_lobby.lua가 다른 로비 키와 함께 원자적으로
     * 수행한다(DEL ... readyKey). 따라서 여기서는 폭파되지 않은 Left/Delegated 경로에서만
     * 퇴장 유저를 ready Set에서 제거한다.
     *
     * [주의]
     * leave_lobby.lua의 원자 처리 이후 보조 정리로 수행한다.
     * ready 정리 실패가 퇴장 자체를 실패로 되돌리면 안 되므로 예외는 로그만 남긴다.
     *
     * @param code 로비 초대 코드
     * @param userId 퇴장 사용자 식별자
     * @param leaveResult 퇴장 Lua 처리 결과
     */
    public void cleanupReadyStatusAfterLeave(
            String code,
            String userId,
            LeaveLobbyResult leaveResult
    ) {
        String readyKey = RedisKeys.lobbyReadyKey(code);

        try {
            if (leaveResult instanceof LeaveLobbyResult.Left
                    || leaveResult instanceof LeaveLobbyResult.Delegated) {
                redisTemplate.opsForSet().remove(readyKey, userId);
            }
        } catch (Exception e) {
            log.error(
                    "{} 퇴장 후 ready 상태 정리 실패 - lobbyCode: {}, userId: {}, readyKey: {}, leaveResult: {}. "
                            + "ready Set 잔여 데이터가 canStart 계산을 왜곡할 수 있으므로 수동 정리 또는 재처리가 필요합니다.",
                    LOG_MONITORING_REQUIRED,
                    code,
                    userId,
                    readyKey,
                    leaveResult.getClass().getSimpleName(),
                    e
            );
        }
    }

    /**
     * 강퇴 성공 후 강퇴 대상의 ready 상태를 제거한다.
     *
     * [필요 이유]
     * 강퇴된 유저가 lobby:{code}:ready Set에 남아 있으면
     * 이후 canStart 계산이나 준비 상태 표시가 왜곡될 수 있다.
     *
     * @param code 로비 초대 코드
     * @param targetUserIdentifier 강퇴 대상 사용자 식별자
     * @param kickResult 강퇴 Lua 처리 결과
     */
    public void cleanupReadyStatusAfterKick(
            String code,
            String targetUserIdentifier,
            KickLobbyResult kickResult
    ) {
        if (!(kickResult instanceof KickLobbyResult.Kicked)) {
            return;
        }

        try {
            redisTemplate.opsForSet().remove(
                    RedisKeys.lobbyReadyKey(code),
                    targetUserIdentifier
            );
        } catch (Exception e) {
            log.warn(
                    "강퇴 후 ready 상태 정리 실패 - lobbyCode: {}, targetUserIdentifier: {}",
                    code,
                    targetUserIdentifier,
                    e
            );
        }
    }

    /**
     * 게임 시작 직전에 stale ready 데이터를 정리한다.
     *
     * [정리 대상]
     * ready Set에는 존재하지만 participants Set에는 없는 userIdentifier
     *
     * [정책]
     * participants Set을 현재 로비 참여자의 source of truth로 사용한다.
     * ready Set 잔여 데이터는 게임 시작 조건에 영향을 주면 안 되므로
     * start_lobby.lua 실행 전에 제거한다.
     *
     * [주의]
     * participants에 남아 있지만 ready가 아닌 유저는 실제 미준비 유저일 수 있으므로 여기서 자동 제거하지 않는다.
     *
     * @param code 로비 초대 코드
     * @param requesterIdentifier 게임 시작 요청자 식별자
     */
    public void cleanupStaleReadyParticipantsBeforeStart(
            String code,
            String requesterIdentifier
    ) {
        String participantsKey = RedisKeys.lobbyParticipantsKey(code);
        String readyKey = RedisKeys.lobbyReadyKey(code);

        try {
            Set<String> participants = redisTemplate.opsForSet().members(participantsKey);
            Set<String> readyParticipants = redisTemplate.opsForSet().members(readyKey);

            if (readyParticipants == null || readyParticipants.isEmpty()) {
                return;
            }

            Set<String> participantSet = participants != null ? participants : Set.of();

            Set<String> staleReadyParticipants = new HashSet<>(readyParticipants);
            staleReadyParticipants.removeAll(participantSet);

            if (staleReadyParticipants.isEmpty()) {
                return;
            }

            redisTemplate.opsForSet().remove(
                    readyKey,
                    staleReadyParticipants.toArray()
            );

            incrementMetric(RedisKeys.METRIC_LOBBY_READY_STALE_CLEANUP);

            log.warn(
                    "{} 게임 시작 전 stale ready 데이터 정리 - lobbyCode: {}, requester: {}, "
                            + "participantsKey: {}, readyKey: {}, staleReadyParticipants: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    requesterIdentifier,
                    participantsKey,
                    readyKey,
                    staleReadyParticipants
            );

        } catch (Exception e) {
            log.error(
                    "{} 게임 시작 전 ready 정합성 스캔 실패 - lobbyCode: {}, requester: {}, "
                            + "participantsKey: {}, readyKey: {}",
                    LOG_MONITORING_REQUIRED,
                    code,
                    requesterIdentifier,
                    participantsKey,
                    readyKey,
                    e
            );
        }
    }

    /**
     * Redis 로비 Hash의 맵 메타데이터 3개 필드를 갱신한다.
     *
     * [정책]
     * - metadata != null이고 모든 필드가 non-null → map_id, map_title, map_category를 단일 HSET으로 원자 갱신
     * - metadata == null 또는 필드 중 하나라도 null → 3개 필드를 HDEL (맵 미선택 상태로 복원)
     * question_count는 항상 갱신된다.
     *
     * [null 필드 처리]
     * 보상 복구 경로에서 기존에 맵이 없던 로비의 oldMetadata는 필드가 모두 null일 수 있다.
     * 이 경우 metadata == null과 동일하게 HDEL로 처리하여 "null" 문자열 오염을 방지한다.
     *
     * @param code          로비 초대 코드
     * @param metadata      새 맵 메타데이터 (null 또는 필드가 하나라도 null이면 맵 필드를 제거한다)
     * @param questionCount 새 문제 수
     */
    public void updateMapMetadata(String code, LobbyMapMetadata metadata, int questionCount) {
        String lobbyKey = RedisKeys.lobbyKey(code);

        if (metadata != null
                && metadata.mapId() != null
                && metadata.mapTitle() != null
                && metadata.mapCategory() != null) {
            redisTemplate.opsForHash().putAll(lobbyKey, Map.of(
                    RedisKeys.FIELD_MAP_ID, String.valueOf(metadata.mapId()),
                    RedisKeys.FIELD_MAP_TITLE, metadata.mapTitle(),
                    RedisKeys.FIELD_MAP_CATEGORY, metadata.mapCategory(),
                    RedisKeys.FIELD_QUESTION_COUNT, String.valueOf(questionCount)
            ));
        } else {
            redisTemplate.opsForHash().delete(
                    lobbyKey,
                    RedisKeys.FIELD_MAP_ID,
                    RedisKeys.FIELD_MAP_TITLE,
                    RedisKeys.FIELD_MAP_CATEGORY
            );
            redisTemplate.opsForHash().put(
                    lobbyKey,
                    RedisKeys.FIELD_QUESTION_COUNT,
                    String.valueOf(questionCount)
            );
        }
    }

    /**
     * Redis 로비 Hash의 설정값을 갱신한다.
     *
     * [갱신 대상]
     * - max_players
     * - question_count
     * - time_limit_seconds
     *
     * [정합성]
     * HSET 한 번으로 같은 Redis Hash의 필드 3개를 갱신한다.
     * Redis 명령 하나는 원자적으로 처리되므로, 조회자가 세 필드 중 일부만 갱신된 상태를 볼 가능성은 없다.
     *
     * [주의]
     * 로비 존재 여부, WAITING 여부, 방장 여부, 현재 참가자 수 검증은 Service 계층에서 완료한 뒤 호출한다.
     *
     * @param code             로비 초대 코드
     * @param maxPlayers       최대 참여 인원
     * @param questionCount    문제 수
     * @param timeLimitSeconds 제한 시간(초)
     */
    public void updateSettings(
            String code,
            int maxPlayers,
            int questionCount,
            int timeLimitSeconds
    ) {
        redisTemplate.opsForHash().putAll(
                RedisKeys.lobbyKey(code),
                Map.of(
                        RedisKeys.FIELD_MAX_PLAYERS, String.valueOf(maxPlayers),
                        RedisKeys.FIELD_QUESTION_COUNT, String.valueOf(questionCount),
                        RedisKeys.FIELD_TIME_LIMIT_SECONDS, String.valueOf(timeLimitSeconds)
                )
        );
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
            log.warn("로비 Redis command metric 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}