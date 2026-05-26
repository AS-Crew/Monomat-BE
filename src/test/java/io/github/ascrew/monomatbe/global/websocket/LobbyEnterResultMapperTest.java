package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LobbyEnterResultMapperTest {

    private final LobbyEnterResultMapper mapper = new LobbyEnterResultMapper();

    @Test
    @DisplayName("ENTERED는 성공 결과로 매핑된다")
    void parse_entered_returnsSuccessType() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("ENTERED");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.ENTERED);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("ALREADY_JOINED는 성공 결과로 매핑된다")
    void parseAlreadyJoinedReturnsSuccessType() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("ALREADY_JOINED");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.ALREADY_JOINED);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("SESSION_REPLACED prefix는 성공 결과로 매핑된다")
    void parseSessionReplacedReturnsSuccessType() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("SESSION_REPLACED:old-session-id");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.SESSION_REPLACED);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("STALE_SESSION prefix는 LOBBY_STALE_SESSION으로 매핑된다")
    void parseStaleSessionReturnsLobbyStaleSession() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("STALE_SESSION:new-session-id");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.STALE_SESSION);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_STALE_SESSION);
    }

    @Test
    @DisplayName("LOBBY_NOT_FOUND는 LOBBY_NOT_FOUND로 매핑된다")
    void parseLobbyNotFoundReturnsLobbyNotFound() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("LOBBY_NOT_FOUND");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.LOBBY_NOT_FOUND);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_NOT_FOUND);
    }

    @Test
    @DisplayName("FULL은 LOBBY_FULL로 매핑된다")
    void parseFullReturnsLobbyFull() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("FULL");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.FULL);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_FULL);
    }

    @Test
    @DisplayName("LOBBY_NOT_WAITING은 LOBBY_NOT_WAITING으로 매핑된다")
    void parseLobbyNotWaitingReturnsLobbyNotWaiting() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("LOBBY_NOT_WAITING");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.LOBBY_NOT_WAITING);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_NOT_WAITING);
    }

    @Test
    @DisplayName("INVALID_LOBBY_CAPACITY는 LOBBY_INVALID_CAPACITY로 매핑된다")
    void parseInvalidLobbyCapacityReturnsLobbyInvalidCapacity() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("INVALID_LOBBY_CAPACITY");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.INVALID_LOBBY_CAPACITY);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_INVALID_CAPACITY);
    }

    @Test
    @DisplayName("KICKED_USER는 LOBBY_KICKED_USER로 매핑된다")
    void parseKickedUserReturnsLobbyKickedUser() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("KICKED_USER");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.KICKED_USER);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_KICKED_USER);
    }

    @Test
    @DisplayName("INVALID_SEQUENCE는 LOBBY_INVALID_SEQUENCE로 매핑된다")
    void parseInvalidSequenceReturnsLobbyInvalidSequence() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("INVALID_SEQUENCE");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.INVALID_SEQUENCE);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_INVALID_SEQUENCE);
    }

    @Test
    @DisplayName("null 결과는 UNKNOWN으로 매핑된다")
    void parseNullReturnsUnknown() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse(null);

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.UNKNOWN);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_ENTER_UNKNOWN_RESULT);
    }

    @Test
    @DisplayName("알 수 없는 결과는 UNKNOWN으로 매핑된다")
    void parseUnknownReturnsUnknown() {
        LobbyEnterResultMapper.LobbyEnterResultType result = mapper.parse("SOMETHING_NEW");

        assertThat(result).isEqualTo(LobbyEnterResultMapper.LobbyEnterResultType.UNKNOWN);
        assertThat(result.resolveErrorCode()).isEqualTo(StompErrorCode.LOBBY_ENTER_UNKNOWN_RESULT);
    }
}