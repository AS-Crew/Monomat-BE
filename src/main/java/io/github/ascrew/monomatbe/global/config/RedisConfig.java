/*
애플리케이션의 Redis 연결 및 실시간 통신 환경을 구성하는 설정 클래스입니다.
주요 설정 내역:

Connection: 비동기 처리에 특화된 Lettuce 클라이언트를 사용하여 Redis 연결
Template: 데이터 캐싱 및 세션 관리를 위한 RedisTemplate의 직렬화(Serializer) 방식 정의
Pub/Sub: 실시간 채팅 및 상태 동기화를 위한 MessageListenerContainer 활성화
 */
package io.github.ascrew.monomatbe.global.config;

import io.github.ascrew.monomatbe.service.RedisSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import java.util.concurrent.Executors;

@Configuration
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // LettuceConnectionFactory를 사용하여 Redis 연결 설정
        return new LettuceConnectionFactory(redisHost, redisPort);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // Key는 String타입 Value는 Object타입으로 RedisTemplate 설정
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);           // Redis 연결 팩토리 설정
        template.setKeySerializer(new StringRedisSerializer());     // 키 직렬화 방식 설정
        template.setValueSerializer(new StringRedisSerializer());   // 값 직렬화 방식 설정
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSubscriber redisSubscriber) {
        // RedisMessageListenerContainer를 생성하여 Redis 메시지 리스너 설정

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 비동기 처리를 위해 가상 스레드 기반의 TaskExecutor 설정 (Java 19 이상에서 사용 가능)
        container.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());

        container.addMessageListener(redisSubscriber, new PatternTopic("/topic/chat/global")); // 전체 채팅 구독
        container.addMessageListener(redisSubscriber, new PatternTopic("/topic/lobby/*")); // 로비 채팅 구독
        return container;
    }


}

