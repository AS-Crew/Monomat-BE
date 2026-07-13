package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StompAccessTokenErrorHandlerTest {

    private final JsonMapper jsonMapper =
            JsonMapper.builder().build();

    private final CustomStompErrorHandler errorHandler =
            new CustomStompErrorHandler(jsonMapper);

    @ParameterizedTest
    @MethodSource("accessTokenErrorCases")
    void accessTokenErrorsAreSerializedAsStructuredPayload(
            StompErrorCode errorCode,
            String expectedAction,
            boolean expectedRecoverable
    ) {
        Message<byte[]> result =
                errorHandler.handleClientMessageProcessingError(
                        emptyClientMessage(),
                        new StompErrorException(errorCode)
                );

        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(result);

        Map<String, Object> payload =
                payloadAsMap(result);

        assertThat(accessor.getCommand())
                .isEqualTo(StompCommand.ERROR);

        assertThat(accessor.getMessage())
                .isEqualTo(errorCode.name());

        assertThat(payload)
                .containsEntry("type", "STOMP_ERROR")
                .containsEntry("code", errorCode.name())
                .containsEntry(
                        "message",
                        errorCode.getDefaultMessage()
                )
                .containsEntry(
                        "action",
                        expectedAction
                )
                .containsEntry(
                        "recoverable",
                        expectedRecoverable
                )
                .containsKey("timestamp");
    }

    private static Stream<Arguments> accessTokenErrorCases() {
        return Stream.of(
                Arguments.of(
                        StompErrorCode.ACCESS_TOKEN_MISSING,
                        "REFRESH_TOKEN",
                        true
                ),
                Arguments.of(
                        StompErrorCode.ACCESS_TOKEN_EXPIRED,
                        "REFRESH_TOKEN",
                        true
                ),
                Arguments.of(
                        StompErrorCode.ACCESS_TOKEN_INVALID,
                        "RELOGIN",
                        false
                ),
                Arguments.of(
                        StompErrorCode.SESSION_REVOKED,
                        "RELOGIN",
                        false
                )
        );
    }

    private Message<byte[]> emptyClientMessage() {
        return MessageBuilder
                .withPayload(new byte[0])
                .build();
    }

    private Map<String, Object> payloadAsMap(
            Message<byte[]> message
    ) {
        try {
            return jsonMapper.readValue(
                    new String(
                            message.getPayload(),
                            StandardCharsets.UTF_8
                    ),
                    new TypeReference<>() {
                    }
            );

        } catch (Exception e) {
            throw new AssertionError(
                    "STOMP ERROR payload JSON 파싱 실패",
                    e
            );
        }
    }
}