/*
 * Redis Pub/Sub 채널을 구독하고 수신된 메시지를 WebSocket으로 브로드캐스트하는 컴포넌트.
 *
 * [동작 흐름]
 * Redis 채널 메시지 수신
 * → raw JSON 문자열 디코딩
 * → ChatMessageDto로 역직렬화하여 메시지 계약 검증
 * → 검증 성공 시 원본 JSON 문자열을 WebSocket으로 브로드캐스트
 *
 * [중요]
 * WebSocket 클라이언트가 수신하는 STOMP message.body는 문자열입니다.
 * 따라서 프론트엔드 계약은 다음과 같이 고정합니다.
 *
 * - message.body는 ChatMessageDto 형태의 JSON 문자열이다.
 * - 클라이언트는 JSON.parse(message.body)로 객체화하여 사용한다.
 *
 * RedisSubscriber에서 ChatMessageDto 객체를 그대로 convertAndSend()하면
 * Spring WebSocket 메시지 변환 과정에서 타입 정보가 포함될 수 있습니다.
 *
 * 문제 예:
 * [
 *   "io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto",
 *   { ... }
 * ]
 *
 * 따라서 WebSocket으로는 DTO 객체가 아니라 검증된 원본 JSON 문자열을 그대로 전달합니다.
 */
package io.github.ascrew.monomatbe.global.redis;

import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class RedisSubscriber implements MessageListener {

    /**
     * 로그에 남길 payload 최대 길이.
     * 잘못된 메시지가 길게 들어왔을 때 로그 오염을 방지한다.
     */
    private static final int MAX_PAYLOAD_LOG_LENGTH = 500;

    /**
     * Redis Pub/Sub 메시지를 WebSocket 구독자에게 전달하는 Spring STOMP 템플릿
     */
    private final SimpMessagingTemplate simpMessagingTemplate;

    /**
     * Pub/Sub 전용 JsonMapper입니다.
     *
     * Redis 데이터 저장용 JsonMapper는 타입 정보를 포함할 수 있으므로,
     * Pub/Sub 메시지 파싱에는 클래스 타입 정보를 요구하지 않는 별도 Mapper를 사용합니다.
     */
    private final JsonMapper pubSubJsonMapper;

    public RedisSubscriber(
            SimpMessagingTemplate simpMessagingTemplate,
            @Qualifier("pubSubJsonMapper") JsonMapper pubSubJsonMapper
    ) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.pubSubJsonMapper = pubSubJsonMapper;
    }

    /**
     * Redis 채널에서 메시지를 수신하면 호출
     *
     * [처리 방식]
     * 1. Redis message.channel을 WebSocket destination으로 사용합니다.
     * 2. Redis message.body를 UTF-8 JSON 문자열로 디코딩합니다.
     * 3. ChatMessageDto로 역직렬화하여 메시지 계약을 검증합니다.
     * 4. 검증 성공 시 원본 JSON 문자열을 그대로 WebSocket으로 전달합니다.
     *
     * [왜 DTO 객체로 보내지 않는가]
     * SimpMessagingTemplate에 DTO 객체를 넘기면 현재 Jackson 설정에 따라
     * WebSocket 응답 body에 클래스 타입 정보가 포함될 수 있습니다.
     * 따라서 프론트 계약을 "JSON 문자열"로 고정하고, 서버는 JSON.parse 가능한 문자열을 전달합니다.
     *
     * @param message Redis 메시지. 채널명과 본문을 포함합니다.
     * @param pattern 패턴 구독 시 매칭된 패턴. 현재 로직에서는 사용하지 않습니다.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String destination = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        try {
            ChatMessageDto chatMessageDto = parseAndValidatePayload(destination, payload);

            if (chatMessageDto == null) {
                return;
            }

            /*
             * WebSocket 클라이언트로는 DTO 객체가 아니라 JSON 문자열을 그대로 전달
             *
             * 프론트 계약:
             * - message.body는 ChatMessageDto JSON 문자열
             * - 클라이언트는 JSON.parse(message.body)로 사용
             *
             * 이 방식은 ["클래스명", {...}] 형태의 타입 정보 노출을 방지한다.
             */
            simpMessagingTemplate.convertAndSend(destination, payload);

        } catch (Exception e) {
            log.error(
                    "Redis Pub/Sub 메시지 WebSocket 브로드캐스트 실패 - destination: {}, payload: {}",
                    destination,
                    abbreviatePayload(payload),
                    e
            );
        }
    }

    /**
     * Redis Pub/Sub payload를 ChatMessageDto로 역직렬화하고 최소 계약을 검증한다.
     *
     * 검증에 성공하면 ChatMessageDto를 반환하고,
     * 실패하면 null을 반환하여 메시지를 폐기한다.
     */
    private ChatMessageDto parseAndValidatePayload(String destination, String payload) {
        ChatMessageDto chatMessageDto;

        try {
            chatMessageDto = pubSubJsonMapper.readValue(payload, ChatMessageDto.class);
        } catch (Exception e) {
            log.error(
                    "Redis Pub/Sub 메시지 역직렬화 실패 - destination: {}, payload: {}",
                    destination,
                    abbreviatePayload(payload),
                    e
            );
            return null;
        }

        if (!isValidMessage(chatMessageDto)) {
            log.warn(
                    "Redis Pub/Sub 메시지 계약 위반 - destination: {}, payload: {}",
                    destination,
                    abbreviatePayload(payload)
            );
            return null;
        }

        return chatMessageDto;
    }

    /**
     * WebSocket으로 전달 가능한 최소 메시지 계약을 검증
     *
     * type과 roomId는 클라이언트 라우팅 및 UI 분기에 필요한 핵심 필드이므로 필수로 본다.
     * sender/content/timestamp는 메시지 유형에 따라 비어 있을 수 있으므로 여기서는 강제하지 않는다.
     */
    private boolean isValidMessage(ChatMessageDto message) {
        return message != null
                && message.getType() != null
                && message.getRoomId() != null
                && !message.getRoomId().isBlank();
    }

    /**
     * payload 로그 길이를 제한
     */
    private String abbreviatePayload(String payload) {
        if (payload == null) {
            return "null";
        }

        if (payload.length() <= MAX_PAYLOAD_LOG_LENGTH) {
            return payload;
        }

        return payload.substring(0, MAX_PAYLOAD_LOG_LENGTH) + "...";
    }
}