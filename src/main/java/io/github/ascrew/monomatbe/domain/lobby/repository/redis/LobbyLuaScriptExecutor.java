package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.dto.CreateLobbyRequest;
import io.github.ascrew.monomatbe.domain.lobby.dto.LobbyMapMetadata;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 로비 관련 Redis Lua script 실행을 담당하는 컴포넌트
 */
@Slf4j
@Component
public class LobbyLuaScriptExecutor {

    /**
     * Lua 스크립트에 선택 값이 없음을 표현하기 위한 값
     * Redis Hash에 "null" 문자열이 저장되는 것을 방지하기 위해 빈 문자열을 사용한다.
     */
    private static final String EMPTY_REDIS_VALUE = "";

    /** Lua 스크립트에 전달할 비공개 로비 isPrivate 값 */
    private static final String IS_PRIVATE_TRUE = "true";

    /** Lua 스크립트에 전달할 공개 로비 isPrivate 값 */
    private static final String IS_PRIVATE_FALSE = "false";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> leaveLobbyScript;
    private final RedisScript<String> createLobbyScript;
    private final RedisScript<String> kickLobbyScript;
    private final RedisScript<String> startLobbyScript;
    private final RedisScript<String> compensateLobbyMapScript;

    public LobbyLuaScriptExecutor(
            StringRedisTemplate redisTemplate,
            @Qualifier("leaveLobbyScript") RedisScript<String> leaveLobbyScript,
            @Qualifier("createLobbyScript") RedisScript<String> createLobbyScript,
            @Qualifier("kickLobbyScript") RedisScript<String> kickLobbyScript,
            @Qualifier("startLobbyScript") RedisScript<String> startLobbyScript,
            @Qualifier("compensateLobbyMapScript") RedisScript<String> compensateLobbyMapScript
    ) {
        this.redisTemplate = redisTemplate;
        this.leaveLobbyScript = leaveLobbyScript;
        this.createLobbyScript = createLobbyScript;
        this.kickLobbyScript = kickLobbyScript;
        this.startLobbyScript = startLobbyScript;
        this.compensateLobbyMapScript = compensateLobbyMapScript;
    }

    /**
     * create_lobby.lua를 실행하여 SETNX + 로비 데이터 저장을 원자적으로 수행한다.
     *
     * [KEYS 계약]
     * KEYS[1] = lobby:{code}:lock
     * KEYS[2] = lobby:{code}
     * KEYS[3] = lobby:public
     * KEYS[4] = lobby:public:latest
     * KEYS[5] = lobby:public:most_players
     * KEYS[6] = lobby:public:most_available
     *
     * [ARGV 계약]
     * ARGV[1]  = host userIdentifier
     * ARGV[2]  = lock ttl milliseconds
     * ARGV[3]  = inviteCode
     * ARGV[4]  = title
     * ARGV[5]  = maxPlayers
     * ARGV[6]  = isPrivate ("true" | "false")
     * ARGV[7]  = status
     * ARGV[8]  = mapId or ""
     * ARGV[9]  = mapTitle or ""
     * ARGV[10] = mapCategory or ""
     * ARGV[11] = questionCount
     * ARGV[12] = timeLimitSeconds
     *
     * @return "OK" | "LOCK_FAILED" | null
     */
    public String executeCreateLobby(
            String inviteCode,
            CreateLobbyRequest request,
            String userIdentifier,
            LobbyMapMetadata mapMetadata,
            int questionCount,
            int timeLimitSeconds
    ) {
        List<String> keys = List.of(
                RedisKeys.lobbyCodeLockKey(inviteCode),
                RedisKeys.lobbyKey(inviteCode),
                RedisKeys.LOBBY_PUBLIC,
                RedisKeys.LOBBY_PUBLIC_LATEST,
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        String lockTtlMs = String.valueOf(LobbyDefaults.INVITE_CODE_LOCK_TTL.toMillis());
        String isPrivateValue = normalizeIsPrivate(request.isPrivate());

        return redisTemplate.execute(
                createLobbyScript,
                keys,
                userIdentifier,
                lockTtlMs,
                inviteCode,
                request.title(),
                String.valueOf(request.maxPlayers()),
                isPrivateValue,
                LobbyStatus.WAITING.name(),

                // 맵 미선택 시 빈 문자열을 전달하여 Redis에 "null" 문자열이 저장되지 않게 한다.
                mapMetadata != null ? String.valueOf(mapMetadata.mapId()) : EMPTY_REDIS_VALUE,
                mapMetadata != null ? mapMetadata.mapTitle() : EMPTY_REDIS_VALUE,
                mapMetadata != null ? mapMetadata.mapCategory() : EMPTY_REDIS_VALUE,
                String.valueOf(questionCount),
                String.valueOf(timeLimitSeconds)
        );
    }

    /**
     * leave_lobby.lua를 실행한다.
     *
     * [KEYS 계약]
     * KEYS[1] = lobby:{code}
     * KEYS[2] = lobby:{code}:participants
     * KEYS[3] = lobby:{code}:order
     * KEYS[4] = lobby:{code}:kicked
     * KEYS[5] = lobby:public
     * KEYS[6] = lobby:public:latest
     * KEYS[7] = lobby:public:most_players
     * KEYS[8] = lobby:public:most_available
     *
     * [ARGV 계약]
     * ARGV[1] = userIdentifier
     * ARGV[2] = lobbyCode
     *
     * @return leave_lobby.lua 반환 문자열
     */
    public String executeLeaveLobby(String code, String userIdentifier) {

        List<String> keys = List.of(
                RedisKeys.lobbyKey(code),
                RedisKeys.lobbyParticipantsKey(code),
                RedisKeys.lobbyOrderKey(code),
                RedisKeys.lobbyKickedKey(code),
                RedisKeys.LOBBY_PUBLIC,
                RedisKeys.LOBBY_PUBLIC_LATEST,
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        return redisTemplate.execute(
                leaveLobbyScript,
                keys,
                userIdentifier,
                code
        );
    }

    /**
     * kick_lobby.lua를 실행한다.
     *
     * [KEYS 계약]
     * KEYS[1] = lobby:{code}
     * KEYS[2] = lobby:{code}:participants
     * KEYS[3] = lobby:{code}:order
     * KEYS[4] = lobby:{code}:kicked
     * KEYS[5] = lobby:{code}:user_session:{targetUserIdentifier}
     * KEYS[6] = lobby:{code}:user_session_seq:{targetUserIdentifier}
     * KEYS[7] = lobby:public:most_players
     * KEYS[8] = lobby:public:most_available
     *
     * [ARGV 계약]
     * ARGV[1] = requesterIdentifier
     * ARGV[2] = targetUserIdentifier
     * ARGV[3] = lobbyCode
     *
     * @return kick_lobby.lua 반환 문자열
     */
    public String executeKickLobby(
            String code,
            String requesterIdentifier,
            String targetUserIdentifier
    ) {
        List<String> keys = List.of(
                RedisKeys.lobbyKey(code),
                RedisKeys.lobbyParticipantsKey(code),
                RedisKeys.lobbyOrderKey(code),
                RedisKeys.lobbyKickedKey(code),
                RedisKeys.lobbyUserSessionKey(code, targetUserIdentifier),
                RedisKeys.lobbyUserSessionSequenceKey(code, targetUserIdentifier),
                RedisKeys.LOBBY_PUBLIC_MOST_PLAYERS,
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        return redisTemplate.execute(
                kickLobbyScript,
                keys,
                requesterIdentifier,
                targetUserIdentifier,
                code
        );
    }

    /**
     * start_lobby.lua를 실행한다.
     *
     * [KEYS 계약]
     * KEYS[1] = lobby:{code}
     * KEYS[2] = lobby:{code}:participants
     * KEYS[3] = lobby:{code}:ready
     *
     * [정책]
     * 게임 시작 후에도 공개 로비 목록에는 PLAYING 로비를 유지한다.
     * 공개 로비 목록 API는 WAITING + PLAYING 상태를 내려주고,
     * 클라이언트는 PLAYING 로비를 "진행 중" 상태로 표시한다.
     *
     * [주의]
     * PLAYING 로비를 목록에 노출하더라도 실제 입장은 허용하지 않는다.
     * enter_lobby.lua는 WAITING 상태 로비만 입장을 허용하므로,
     * PLAYING 로비 클릭 시 FE는 입장 요청을 보내지 않거나 비활성화 처리해야 한다.
     *
     * [ARGV 계약]
     * ARGV[1] = requesterIdentifier
     * ARGV[2] = lobbyCode
     * ARGV[3] = host field name
     * ARGV[4] = status field name
     * ARGV[5] = mapId field name
     * ARGV[6] = WAITING status
     * ARGV[7] = PLAYING status
     *
     * @return start_lobby.lua 반환 문자열
     */
    public String executeStartLobby(
            String code,
            String requesterIdentifier
    ) {
        List<String> keys = List.of(
                RedisKeys.lobbyKey(code),
                RedisKeys.lobbyParticipantsKey(code),
                RedisKeys.lobbyReadyKey(code)
        );

        return redisTemplate.execute(
                startLobbyScript,
                keys,
                requesterIdentifier,
                code,
                RedisKeys.FIELD_HOST_USER_ID,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_MAP_ID,
                LobbyStatus.WAITING.name(),
                LobbyStatus.PLAYING.name()
        );
    }

    /**
     * compensate_lobby_map.lua를 실행한다.
     *
     * [KEYS 계약]
     * KEYS[1] = lobby:{code}
     *
     * [ARGV 계약]
     * ARGV[1]  = status field name
     * ARGV[2]  = mapId field name
     * ARGV[3]  = mapTitle field name
     * ARGV[4]  = mapCategory field name
     * ARGV[5]  = WAITING status
     * ARGV[6]  = oldMapId (없으면 "")
     * ARGV[7]  = oldMapTitle (없으면 "")
     * ARGV[8]  = oldMapCategory (없으면 "")
     * ARGV[9]  = questionCount field name
     * ARGV[10] = oldQuestionCount
     *
     * [null oldMetadata 처리]
     * oldMetadata가 null이거나 필드가 일부 null이면 빈 문자열로 전달하여
     * Lua가 HDEL로 처리하도록 한다. 이는 "이전이 맵 미선택 상태였음"을 의미한다.
     *
     * @return "COMPENSATED" | "SKIPPED_NOT_WAITING" | "LOBBY_NOT_FOUND"
     */
    public String executeCompensateLobbyMap(String code, LobbyMapMetadata oldMetadata, int oldQuestionCount) {
        List<String> keys = List.of(RedisKeys.lobbyKey(code));

        boolean restoreMap = oldMetadata != null
                && oldMetadata.mapId() != null
                && oldMetadata.mapTitle() != null
                && oldMetadata.mapCategory() != null;

        return redisTemplate.execute(
                compensateLobbyMapScript,
                keys,
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_MAP_ID,
                RedisKeys.FIELD_MAP_TITLE,
                RedisKeys.FIELD_MAP_CATEGORY,
                LobbyStatus.WAITING.name(),
                restoreMap ? String.valueOf(oldMetadata.mapId()) : EMPTY_REDIS_VALUE,
                restoreMap ? oldMetadata.mapTitle() : EMPTY_REDIS_VALUE,
                restoreMap ? oldMetadata.mapCategory() : EMPTY_REDIS_VALUE,
                RedisKeys.FIELD_QUESTION_COUNT,
                String.valueOf(oldQuestionCount)
        );
    }

    /**
     * isPrivate 값을 Lua 스크립트와 일치하는 소문자 문자열로 정규화한다.
     *
     * [정규화 이유]
     * create_lobby.lua는 isPrivate 값을 문자열 "false"와 비교하여 공개 로비 여부를 판단한다.
     * Boolean.toString()에 직접 의존하지 않고 상수로 고정하여 Lua 계약을 명확히 유지한다.
     */
    private String normalizeIsPrivate(boolean isPrivate) {
        return isPrivate ? IS_PRIVATE_TRUE : IS_PRIVATE_FALSE;
    }
}