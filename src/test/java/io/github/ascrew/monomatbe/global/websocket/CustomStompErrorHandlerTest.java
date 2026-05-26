package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomStompErrorHandlerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final CustomStompErrorHandler errorHandler =
            new CustomStompErrorHandler(jsonMapper);

    @Test
    @DisplayName("StompErrorException은 표준 STOMP ERROR JSON payload로 변환된다")
    void handleClientMessageProcessingError_withStompErrorException_returnsStandardJsonPayload() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        StompErrorException exception = new StompErrorException(
                StompErrorCode.LOBBY_NOT_FOUND
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                exception
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("LOBBY_NOT_FOUND");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "LOBBY_NOT_FOUND")
                .containsEntry("message", "존재하지 않는 로비입니다.")
                .containsEntry("action", "RETURN_TO_LOBBY_LIST")
                .containsEntry("recoverable", false);

        assertTimestampExists(payload);
    }

    @Test
    @DisplayName("강퇴된 유저 재입장 실패는 RETURN_TO_LOBBY_LIST 액션과 recoverable=false로 응답한다")
    void handleClientMessageProcessingError_withKickedUser_returnsReturnToLobbyListPayload() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        StompErrorException exception = new StompErrorException(
                StompErrorCode.LOBBY_KICKED_USER
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                exception
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("LOBBY_KICKED_USER");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "LOBBY_KICKED_USER")
                .containsEntry("message", "강퇴된 로비에는 재입장할 수 없습니다.")
                .containsEntry("action", "RETURN_TO_LOBBY_LIST")
                .containsEntry("recoverable", false);

        assertTimestampExists(payload);
    }

    @Test
    @DisplayName("stale 세션 실패는 RECONNECT 액션과 recoverable=true로 응답한다")
    void handleClientMessageProcessingError_withStaleSession_returnsReconnectPayload() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        StompErrorException exception = new StompErrorException(
                StompErrorCode.LOBBY_STALE_SESSION
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                exception
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("LOBBY_STALE_SESSION");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "LOBBY_STALE_SESSION")
                .containsEntry("message", "더 최신 WebSocket 세션이 이미 존재합니다. 다시 접속해주세요.")
                .containsEntry("action", "RECONNECT")
                .containsEntry("recoverable", true);

        assertTimestampExists(payload);
    }

    @Test
    @DisplayName("MessageDeliveryException으로 래핑된 StompErrorException도 원래 에러 코드로 변환된다")
    void handleClientMessageProcessingError_withWrappedStompErrorException_returnsOriginalErrorCode() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        StompErrorException cause = new StompErrorException(
                StompErrorCode.LOBBY_FULL
        );

        MessageDeliveryException wrappedException = new MessageDeliveryException(
                clientMessage,
                "STOMP message delivery failed",
                cause
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                wrappedException
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("LOBBY_FULL");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "LOBBY_FULL")
                .containsEntry("message", "로비 최대 인원에 도달했습니다.")
                .containsEntry("action", "RETURN_TO_LOBBY_LIST")
                .containsEntry("recoverable", false);

        assertTimestampExists(payload);
    }

    @Test
    @DisplayName("중간 cause에 IllegalStateException이 있어도 더 깊은 StompErrorException을 우선한다")
    void handleClientMessageProcessingError_withNestedStompErrorException_prioritizesStompErrorException() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        StompErrorException stompErrorException = new StompErrorException(
                StompErrorCode.LOBBY_FULL
        );

        IllegalStateException intermediateException = new IllegalStateException(
                "wrapped legacy exception",
                stompErrorException
        );

        MessageDeliveryException wrappedException = new MessageDeliveryException(
                clientMessage,
                "STOMP message delivery failed",
                intermediateException
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                wrappedException
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("LOBBY_FULL");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "LOBBY_FULL")
                .containsEntry("message", "로비 최대 인원에 도달했습니다.")
                .containsEntry("action", "RETURN_TO_LOBBY_LIST")
                .containsEntry("recoverable", false);

        assertTimestampExists(payload);
    }

    @Test
    @DisplayName("표준화되지 않은 IllegalStateException은 INTERNAL_STOMP_ERROR로 변환된다")
    void handleClientMessageProcessingError_withNonStandardIllegalStateException_returnsInternalStompError() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        IllegalStateException exception = new IllegalStateException(
                "legacy stomp error"
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                exception
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("INTERNAL_STOMP_ERROR");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "INTERNAL_STOMP_ERROR")
                .containsEntry("message", "WebSocket 처리 중 서버 오류가 발생했습니다.")
                .containsEntry("action", "REFRESH_AND_RETRY")
                .containsEntry("recoverable", true);

        assertTimestampExists(payload);
    }

    @Test
    @DisplayName("예상하지 못한 RuntimeException도 INTERNAL_STOMP_ERROR로 변환된다")
    void handleClientMessageProcessingError_withUnexpectedRuntimeException_returnsInternalStompError() {
        // given
        Message<byte[]> clientMessage = emptyClientMessage();

        RuntimeException exception = new RuntimeException(
                "unexpected error"
        );

        // when
        Message<byte[]> result = errorHandler.handleClientMessageProcessingError(
                clientMessage,
                exception
        );

        // then
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> payload = payloadAsMap(result);

        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getMessage()).isEqualTo("INTERNAL_STOMP_ERROR");

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", "INTERNAL_STOMP_ERROR")
                .containsEntry("message", "WebSocket 처리 중 서버 오류가 발생했습니다.")
                .containsEntry("action", "REFRESH_AND_RETRY")
                .containsEntry("recoverable", true);

        assertTimestampExists(payload);
    }

    private Message<byte[]> emptyClientMessage() {
        return MessageBuilder.withPayload(new byte[0]).build();
    }

    private Map<String, Object> payloadAsMap(Message<byte[]> message) {
        try {
            return jsonMapper.readValue(
                    payloadAsString(message),
                    new TypeReference<>() {}
            );
        } catch (Exception e) {
            throw new AssertionError("STOMP ERROR payload JSON 파싱 실패", e);
        }
    }

    private String payloadAsString(Message<byte[]> message) {
        return new String(message.getPayload(), StandardCharsets.UTF_8);
    }

    private void assertTimestampExists(Map<String, Object> payload) {
        assertThat(payload)
                .containsKey("timestamp");

        assertThat(payload.get("timestamp"))
                .isInstanceOf(String.class)
                .asString()
                .isNotBlank();
    }
}