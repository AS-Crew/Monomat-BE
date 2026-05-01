/*
 * Redis Pub/Sub 채널을 구독하고 수신된 메시지를 WebSocket으로 브로드캐스트하는 컴포넌트.
 *
 * [동작 흐름]
 * Redis 채널 메시지 수신 → JSON 역직렬화 → SimpMessagingTemplate으로 WebSocket 브로드캐스트
 *
 * [global/redis로 분류한 이유]
 * RedisPublisher와 마찬가지로 도메인에 종속되지 않는 인프라 컴포넌트
 */
package io.github.ascrew.monomatbe.global.redis;

import io.github.ascrew.monomatbe.domain.chat.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriber implements MessageListener {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate simpMessagingTemplate;

    /**
     * Redis 채널에서 메시지를 수신하면 호출됩니다.
     * 수신된 바이트 배열을 ChatMessageDto로 역직렬화한 뒤
     * 해당 채널을 구독 중인 WebSocket 클라이언트들에게 전달합니다.
     *
     * @param message Redis 메시지 (채널명 + 본문 포함)
     * @param pattern 패턴 구독 시 매칭된 패턴 (ChannelTopic 구독 시 null)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // Redis 메시지 본문을 ChatMessageDto로 역직렬화
            ChatMessageDto chatMessageDto =
                    (ChatMessageDto) redisTemplate.getValueSerializer().deserialize(message.getBody());

            if (chatMessageDto != null) {
                // 수신된 채널 경로 그대로 WebSocket 구독자에게 브로드캐스트
                String destination = new String(message.getChannel());
                simpMessagingTemplate.convertAndSend(destination, chatMessageDto);
            }

        } catch (Exception e) {
            log.error("Redis 메시지 역직렬화 또는 브로드캐스트 실패 - 채널: {}",
                    new String(message.getChannel()), e);
        }
    }
}