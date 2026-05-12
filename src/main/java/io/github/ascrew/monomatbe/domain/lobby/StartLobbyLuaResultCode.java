package io.github.ascrew.monomatbe.domain.lobby;

import java.util.Arrays;
import java.util.Optional;

/**
 * start_lobby.lua 반환 문자열 계약
 *
 * [중요]
 * 이 enum의 wireValue는 start_lobby.lua의 return 문자열과 반드시 일치해야 한다.
 * Lua 스크립트와 Java 파서가 다른 버전으로 배포되면 게임 시작 실패로 이어질 수 있으므로, contract test로 문자열 계약을 고정한다.
 */
public enum StartLobbyLuaResultCode {

    STARTED("STARTED"),
    LOBBY_NOT_FOUND("LOBBY_NOT_FOUND"),
    HOST_NOT_FOUND("HOST_NOT_FOUND"),
    FORBIDDEN("FORBIDDEN"),
    LOBBY_NOT_WAITING("LOBBY_NOT_WAITING"),
    MAP_NOT_SELECTED("MAP_NOT_SELECTED"),
    NO_PLAYER("NO_PLAYER"),
    NOT_READY_PREFIX("NOT_READY:");

    private final String wireValue;

    StartLobbyLuaResultCode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<StartLobbyLuaResultCode> fromExactValue(String value) {
        return Arrays.stream(values())
                .filter(code -> !code.equals(NOT_READY_PREFIX))
                .filter(code -> code.wireValue.equals(value))
                .findFirst();
    }

    public static boolean isNotReadyResult(String value) {
        return value != null && value.startsWith(NOT_READY_PREFIX.wireValue);
    }

    public static String extractNotReadyUserIdentifier(String value) {
        return value.substring(NOT_READY_PREFIX.wireValue.length());
    }
}