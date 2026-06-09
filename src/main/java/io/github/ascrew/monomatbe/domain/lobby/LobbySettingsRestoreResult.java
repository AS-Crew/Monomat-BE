package io.github.ascrew.monomatbe.domain.lobby;

/**
 * 로비 설정 복구 Redis Lua 처리 결과
 *
 * [처리 대상]
 * - 로비 존재 여부 확인
 * - WAITING 상태 확인
 * - 현재 참가자 수 <= 복구할 maxPlayers 확인
 * - 설정 Hash 복구
 * - 공개 로비 빈자리 인덱스 복구
 */
public enum LobbySettingsRestoreResult {
    RESTORED,
    LOBBY_NOT_FOUND,
    NOT_WAITING,
    MAX_PLAYERS_LESS_THAN_CURRENT_PLAYERS,
    ERROR;

    public static LobbySettingsRestoreResult from(String value) {
        if (value == null || value.isBlank()) {
            return ERROR;
        }

        try {
            return LobbySettingsRestoreResult.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ERROR;
        }
    }
}