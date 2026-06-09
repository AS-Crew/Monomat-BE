package io.github.ascrew.monomatbe.domain.lobby.repository.redis;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyStatus;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LobbyLuaScriptExecutorTest {

    private static final String CODE = "ABC123";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> leaveLobbyScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> createLobbyScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> kickLobbyScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> startLobbyScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> compensateLobbyMapScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> reapLobbyScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> updateLobbySettingsScript = mock(RedisScript.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<String> restoreLobbySettingsScript = mock(RedisScript.class);

    private final LobbyLuaScriptExecutor sut = new LobbyLuaScriptExecutor(
            redisTemplate,
            leaveLobbyScript,
            createLobbyScript,
            kickLobbyScript,
            startLobbyScript,
            compensateLobbyMapScript,
            reapLobbyScript,
            updateLobbySettingsScript,
            restoreLobbySettingsScript
    );

    @Test
    @DisplayName("로비 설정 변경 Lua 실행 시 빈자리 정렬 인덱스 키와 필요한 필드명을 함께 전달한다")
    void executeUpdateLobbySettings_passesMostAvailableIndexAndFieldNames() {
        sut.executeUpdateLobbySettings(
                CODE,
                4,
                10,
                30
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);

        ArgumentCaptor<String> argCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate).execute(
                eq(updateLobbySettingsScript),
                keysCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture()
        );

        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.lobbyKey(CODE),
                RedisKeys.lobbyParticipantsKey(CODE),
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        assertThat(argCaptor.getAllValues()).containsExactly(
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_MAX_PLAYERS,
                RedisKeys.FIELD_QUESTION_COUNT,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                LobbyStatus.WAITING.name(),
                RedisKeys.FIELD_IS_PRIVATE,
                RedisKeys.FIELD_CURRENT_PLAYERS,
                CODE,
                "4",
                "10",
                "30"
        );
    }

    @Test
    @DisplayName("로비 설정 복구 Lua 실행 시 빈자리 정렬 인덱스 키와 필요한 필드명을 함께 전달한다")
    void executeRestoreLobbySettings_passesMostAvailableIndexAndFieldNames() {
        sut.executeRestoreLobbySettings(
                CODE,
                6,
                5,
                30
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);

        ArgumentCaptor<String> argCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate).execute(
                eq(restoreLobbySettingsScript),
                keysCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture(),
                argCaptor.capture()
        );

        assertThat(keysCaptor.getValue()).containsExactly(
                RedisKeys.lobbyKey(CODE),
                RedisKeys.lobbyParticipantsKey(CODE),
                RedisKeys.LOBBY_PUBLIC_MOST_AVAILABLE
        );

        assertThat(argCaptor.getAllValues()).containsExactly(
                RedisKeys.FIELD_STATUS,
                RedisKeys.FIELD_MAX_PLAYERS,
                RedisKeys.FIELD_QUESTION_COUNT,
                RedisKeys.FIELD_TIME_LIMIT_SECONDS,
                LobbyStatus.WAITING.name(),
                RedisKeys.FIELD_IS_PRIVATE,
                RedisKeys.FIELD_CURRENT_PLAYERS,
                CODE,
                "6",
                "5",
                "30"
        );
    }
}