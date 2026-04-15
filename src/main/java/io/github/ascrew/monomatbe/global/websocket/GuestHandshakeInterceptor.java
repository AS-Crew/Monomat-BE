package io.github.ascrew.monomatbe.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class GuestHandshakeInterceptor  implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) throws Exception {
        log.info("WebSocket Handshake Intercepted: {}", request.getURI());
        // 여기에 인증 로직을 추가할 수 있습니다. 예를 들어, JWT 토큰 검증 등을 수행할 수 있습니다.
        // 현재는 모든 요청을 허용하도록 설정되어 있습니다.
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        log.info("WebSocket Handshake Completed: {}", request.getURI());
    }
}
