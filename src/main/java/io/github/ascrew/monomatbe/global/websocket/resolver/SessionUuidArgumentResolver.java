package io.github.ascrew.monomatbe.global.websocket.resolver;

import io.github.ascrew.monomatbe.global.annotation.SessionUuid;
import org.jspecify.annotations.NonNull;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SessionUuidArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // @SessionUuid 어노테이션이 붙어있고 타입이 String인 파라미터만 처리
        return parameter.hasParameterAnnotation(SessionUuid.class)
                && parameter.getParameterType().equals(String.class);
    }

    @Override
    public @NonNull Object resolveArgument(@NonNull MethodParameter parameter, @NonNull Message<?> message) {
        // WebSocket 세션에서 UUID를 추출하여 반환
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();

        if (sessionAttributes != null && sessionAttributes.get("uuid") != null) {
            return sessionAttributes.get("uuid");
        }
        return "UNKNOWN"; // 세션에 uuid가 없을 경우 기본값 반환, 필요에 따라 예외를 던질 수도 있음
    }
}
