/*
Query String방식을 예상하고 만들었는데 필요없어짐

package io.github.ascrew.monomatbe.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServletServerHttpRequest;
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


        if (request instanceof ServletServerHttpRequest) {
            log.info("WebSocket Handshake Intercepted: {}", request.getURI()); // 핸드쉐이크 요청이 들어올 때마다 로그에 URI를 기록하여 디버깅에 도움을 줌

            String uuid = ((ServletServerHttpRequest) request).getServletRequest().getParameter("uuid");

            if (uuid == null || uuid.trim().isEmpty()) {
                return false; // uuid가 없거나 빈 문자열인 경우, 핸드쉐이크를 거부하여 연결을 차단
            }

            attributes.put("uuid", uuid);   // WebSocket 세션에서 uuid를 사용할 수 있도록 attributes에 저장
        }

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
*/
