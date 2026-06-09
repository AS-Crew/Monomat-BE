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
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LobbyRedisCommandRepository {

    private static final String LOG_MONITORING_REQUIRED = "[MONITORING_REQUIRED]";

    private static final String IS_PRIVATE_FALSE = "false";
    private static final String IS_PRIVATE_TRUE = "true";

    private final StringRedisTemplate redisTemplate;

    public void updateReadyStatus(String code, String userIdentifier, boolean ready) {
        String readyKey = RedisKeys.lobbyReadyKey(code);

        if (ready) {
            redisTemplate.opsForSet().add(readyKey, userIdentifier);
            return;
        }

        redisTemplate.opsForSet().remove(readyKey, userIdentifier);
    }

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

    private void incrementMetric(String metricKey) {
        try {
            redisTemplate.opsForValue().increment(metricKey);
        } catch (Exception e) {
            log.warn("로비 Redis command metric 증가 실패 - metricKey: {}", metricKey, e);
        }
    }
}