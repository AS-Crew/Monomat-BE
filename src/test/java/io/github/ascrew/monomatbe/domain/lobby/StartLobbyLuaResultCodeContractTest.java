package io.github.ascrew.monomatbe.domain.lobby;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StartLobbyLuaResultCodeContractTest {

    private static final String START_LOBBY_SCRIPT_PATH = "scripts/start_lobby.lua";

    @Test
    void start_lobby_lua_반환값은_Java_enum_계약과_일치해야_한다() throws Exception {
        String script = new ClassPathResource(START_LOBBY_SCRIPT_PATH)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.STARTED));
        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.LOBBY_NOT_FOUND));
        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.HOST_NOT_FOUND));
        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.FORBIDDEN));
        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.LOBBY_NOT_WAITING));
        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.MAP_NOT_SELECTED));
        assertThat(script).contains(returnLiteral(StartLobbyLuaResultCode.NO_PLAYER));

        assertThat(script).contains("'NOT_READY:'");
    }

    private String returnLiteral(StartLobbyLuaResultCode resultCode) {
        return "return '" + resultCode.wireValue() + "'";
    }
}