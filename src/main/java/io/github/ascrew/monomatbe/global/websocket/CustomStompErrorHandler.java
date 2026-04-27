package io.github.ascrew.monomatbe.global.websocket;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;


@Component
public class CustomStompErrorHandler extends StompSubProtocolErrorHandler {

    public CustomStompErrorHandler() {
        super();
    }
    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        // 인터셉터에서 발생한 예외는 보통 MessageDeliveryException 등으로 래핑되어 전달됩니다.
        // ex.getCause()를 통해 우리가 직접 던진 실제 예외(IllegalArgumentException 등)를 추출합니다.
        Throwable cause = ex.getCause();

        // 의도한 예외 (인증 실패, 권한 부족 등)인 경우
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return handleCustomException(cause.getMessage());
        }

        // 그 외의 예상치 못한 서버 에러인 경우 기본 처리
        return super.handleClientMessageProcessingError(clientMessage, ex);
    }

    private Message<byte[]> handleCustomException(String errorMessage) {
        // STOMP의 ERROR 프레임 생성
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);

        // 프론트엔드가 에러 원인을 파악할 수 있도록 메시지(message) 헤더 세팅
        accessor.setMessage(errorMessage);
        accessor.setLeaveMutable(true);

        // 에러 상세 내용을 Body에 담아 전송
        return MessageBuilder.createMessage(
                errorMessage.getBytes(StandardCharsets.UTF_8),
                accessor.getMessageHeaders()
        );

    }
}

