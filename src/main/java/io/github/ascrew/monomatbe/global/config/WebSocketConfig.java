/*
1.기본 연결 정보
    엔드포인트(접속 주소): http://{서버_도메인_또는_IP}:8080/ws
2.통신 경로 규칙
    STOMP 통신 시 사용하는 경로 규칙
    - 송신용 경로: /app/** (클라이언트가 메시지를 보낼 때 사용하는 경로)
    - 수신용 경로: /topic/** (서버가 클라이언트에게 메시지를 보낼 때 사용하는 경로)
3.메시지 브로커
    - Simple Broker: /topic/** (서버가 클라이언트에게 메시지를 보낼 때 사용하는 경로)
    - Application Destination Prefix: /app/** (클라이언트가 메시지를 보낼 때 사용하는 경로)
4.웹소켓 연결 실패 시 대체 통신 방법
    - SockJS: 웹소켓 연결이 실패할 경우, 폴링 등의 대체 통신 방법을 사용하여 연결을 유지할 수 있도록 지원
 */








package io.github.ascrew.monomatbe.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void registerStompEndpoints(org.springframework.web.socket.config.annotation.StompEndpointRegistry registry) {
        registry
                .addEndpoint("/ws") //최초로 웹소켓 연결을 하기위해 url /ws 지정
                .setAllowedOriginPatterns("*") //모든 도메인에서 접속을 허용
                .withSockJS(); //웹소켓 연결 실패시 일반 HTTP통신으로 연결
    }

    @Override
    public void configureMessageBroker(org.springframework.messaging.simp.config.MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic"); //수신용
        registry.setApplicationDestinationPrefixes("/app");  //송신용(발행)
    }

}
