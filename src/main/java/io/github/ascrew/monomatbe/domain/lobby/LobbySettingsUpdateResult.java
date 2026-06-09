package io.github.ascrew.monomatbe.domain.lobby;

/**
 * 로비 설정 수정 Redis Lua 처리 결과
 *
 * [처리 대상]
 * - 로비 존재 여부 확인
 * - WAITING 상태 확인
 * - 현재 참가자 수 <= maxPlayers 확인
 * - 설정 Hash 갱신
 */
public enum LobbySettingsUpdateResult {
    UPDATED,
    LOBBY_NOT_FOUND,
    NOT_WAITING,
    MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS,
    ERROR;

    public static LobbySettingsUpdateResult from(String value) {
        if (value == null || value.isBlank()) {
            return ERROR;
        }

        try {
            return LobbySettingsUpdateResult.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ERROR;
        }
    }
}