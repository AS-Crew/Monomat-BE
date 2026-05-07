/*
 * Redis Pub/Sub 채널을 구독하고 수신된 메시지를 WebSocket으로 브로드캐스트하는 컴포넌트.
 *
 * [동작 흐름]
 * Redis 채널 메시지 수신
 * → JSON 문자열로 디코딩
 * → 동일한 destination을 구독 중인 WebSocket 클라이언트에게 그대로 전달
 *
 * [중요]
 * 여기서는 ChatMessageDto로 역직렬화하지 않습니다.
 * Pub/Sub 메시지는 이미 RedisPublisher에서 프론트엔드 친화적인 순수 JSON 문자열로 직렬화되었습니다.
 *
 * 역직렬화를 생략하면 다음 장점이 있습니다.
 * - 클래스 타입 정보가 클라이언트로 노출되지 않음
 * - RedisTemplate<String, Object> 직렬화 정책과 분리됨
 * - 메시지 릴레이 책임만 수행하므로 구조가 단순해짐
 */
package io.github.ascrew.monomatbe.global.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final SimpMessagingTemplate simpMessagingTemplate;

    /**
     * Redis 채널에서 메시지를 수신하면 호출된다.
     *
     * [처리 방식]
     * - message.channel: WebSocket destination으로 사용한다.
     * - message.body   : JSON 문자열로 디코딩한 뒤 그대로 전달한다.
     *
     * @param message Redis 메시지. 채널명과 본문을 포함한다.
     * @param pattern 패턴 구독 시 매칭된 패턴. 현재 로직에서는 사용하지 않는다.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String destination = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        try {
            simpMessagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.error("Redis Pub/Sub 메시지 WebSocket 브로드캐스트 실패 - destination: {}, payload: {}",
                    destination,
                    payload,
                    e);
        }
    }
}