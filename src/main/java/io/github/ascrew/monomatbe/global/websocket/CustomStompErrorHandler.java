package io.github.ascrew.monomatbe.global.websocket;

import io.github.ascrew.monomatbe.global.websocket.error.StompErrorCode;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorException;
import io.github.ascrew.monomatbe.global.websocket.error.StompErrorPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * STOMP ERROR 프레임을 표준 JSON payload로 변환하는 핸들러
 *
 * [기존 문제]
 * 기존 구현은 ERROR body에 문자열만 내려주었기 때문에 FE가 메시지 문자열을 파싱해야 했음
 *
 * [변경 후]
 * code/action/recoverable을 포함한 JSON payload를 내려주어
 * FE가 안정적으로 화면 복귀, 재연결, 새로고침 재시도 여부를 판단할 수 있다.
 */
@Slf4j
@Component
public class CustomStompErrorHandler extends StompSubProtocolErrorHandler {

    private static final String HEADER_ERROR_CODE = "error-code";

    private final JsonMapper jsonMapper;

    public CustomStompErrorHandler(
            @Qualifier("cacheJsonMapper") JsonMapper jsonMapper
    ) {
        super();
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(
            Message<byte[]> clientMessage,
            Throwable ex
    ) {
        Throwable handledException = findHandledException(ex);

        if (handledException instanceof StompErrorException stompErrorException) {
            return buildErrorMessage(StompErrorPayload.from(stompErrorException));
        }

        if (handledException instanceof IllegalArgumentException
                || handledException instanceof IllegalStateException) {
            log.warn("표준화되지 않은 STOMP 예외 감지 - message: {}", handledException.getMessage());

            StompErrorException fallbackException = new StompErrorException(
                    StompErrorCode.INTERNAL_STOMP_ERROR
            );

            return buildErrorMessage(StompErrorPayload.from(fallbackException));
        }

        log.error("예상하지 못한 STOMP 처리 오류", ex);

        return buildErrorMessage(StompErrorPayload.from(StompErrorCode.INTERNAL_STOMP_ERROR));
    }

    /**
     * Spring WebSocket 내부에서 예외가 MessageDeliveryException 등으로 래핑될 수 있으므로
     * cause chain을 순회하면서 처리 가능한 예외를 찾는다.
     *
     * [중요]
     * StompErrorException은 FE 계약에 직접 연결되는 code/action/recoverable을 가진 예외다.
     * 따라서 IllegalStateException / IllegalArgumentException보다 항상 우선해야 한다.
     *
     * 예:
     * MessageDeliveryException
     *   └── IllegalStateException
     *       └── StompErrorException(LOBBY_FULL)
     *
     * 위 구조에서 중간의 IllegalStateException을 먼저 반환하면 LOBBY_FULL이 INTERNAL_STOMP_ERROR로 손실된다.
     */
    private Throwable findHandledException(Throwable throwable) {
        StompErrorException stompErrorException = findCause(
                throwable,
                StompErrorException.class
        );

        if (stompErrorException != null) {
            return stompErrorException;
        }

        Throwable legacyException = findLegacyClientException(throwable);

        if (legacyException != null) {
            return legacyException;
        }

        return throwable;
    }

    /**
     * cause chain 전체에서 targetType에 해당하는 예외를 찾는다.
     */
    private <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> targetType
    ) {
        Throwable current = throwable;

        while (current != null) {
            if (targetType.isInstance(current)) {
                return targetType.cast(current);
            }

            current = current.getCause();
        }

        return null;
    }

    /**
     * 기존 코드에서 사용하던 IllegalArgumentException / IllegalStateException 기반 STOMP 예외를 찾는다.
     *
     * 이 예외들은 구체적인 StompErrorCode를 갖고 있지 않으므로,
     * INTERNAL_STOMP_ERROR로 fallback 처리한다.
     */
    private Throwable findLegacyClientException(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof IllegalArgumentException
                    || current instanceof IllegalStateException) {
                return current;
            }

            current = current.getCause();
        }

        return null;
    }

    /**
     * 표준 STOMP ERROR 메시지를 생성한다.
     *
     * message 헤더에는 error code를 넣고,
     * body에는 JSON payload를 넣는다.
     */
    private Message<byte[]> buildErrorMessage(StompErrorPayload payload) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);

        accessor.setMessage(payload.code());
        accessor.setNativeHeader(HEADER_ERROR_CODE, payload.code());
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
                serializePayload(payload),
                accessor.getMessageHeaders()
        );
    }

    private byte[] serializePayload(StompErrorPayload payload) {
        try {
            return jsonMapper.writeValueAsString(payload)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("STOMP ERROR payload 직렬화 실패 - code: {}", payload.code(), e);

            String fallbackJson = """
                    {"type":"STOMP_ERROR","code":"INTERNAL_STOMP_ERROR","message":"WebSocket 처리 중 서버 오류가 발생했습니다.","action":"REFRESH_AND_RETRY","recoverable":true,"timestamp":"%s"}
                    """.formatted(payload.timestamp());

            return fallbackJson.getBytes(StandardCharsets.UTF_8);
        }
    }
}