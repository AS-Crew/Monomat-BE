/*
애플리케이션의 Redis 연결 및 실시간 통신 환경을 구성하는 설정 클래스입니다.
[주요 설정]
- Connection: 비동기 처리에 특화된 Lettuce 클라이언트로 Redis 연결
- Serializer : JsonMapper (Jackson 3.x)를 단일 bean으로 관리하여 RedisTemplate의 직렬화 설정을 중앙화함
             : activateDefaultTyping(NON_FINAL) 설정으로 역직렬화 시 타입 정보를 보존함
- Pub/Sub: 실시간 채팅 및 상태 동기화를 위한 MessageListenerContainer 활성화
 */
package io.github.ascrew.monomatbe.global.config;

import tools.jackson.databind.json.JsonMapper;
import io.github.ascrew.monomatbe.service.RedisSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.DefaultTyping;

import java.util.concurrent.Executors;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    /**
     * Redis 연결 팩토리 Bean
     * Lettuce 클라이언트를 사용하여 가상 스레드 핀닝 문제를 방지한다.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // LettuceConnectionFactory를 사용하여 Redis 연결 설정
        return new LettuceConnectionFactory(redisHost, redisPort);
    }

    // RedisTemplate의 값 (Value) 직렬화에 사용되는 핵심 설정 객체이다.
    // 이 Bean을 중앙에서 단일하게 관리함으로써, 직렬화 정책 변경 시 이 메서드 하나만 수정하면 된다.
    @Bean
    public JsonMapper jsonMapper() {
        // 역직렬화 허용 패키지를 화이트리스트 방식으로 명시적으로 제한
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("io.github.ascrew.monomatbe.") // 프로젝트 내 DTO 허용
                .allowIfSubType("java.util.")                  // List, Map 등 컬렉션 허용
                .allowIfSubType("java.lang.")                  // String, Integer 등 기본 타입 허용
                .build();

        return JsonMapper.builder()
                // NON_FINAL 클래스에 한해 @class 타입 정보를 JSON에 포함
                .activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL)
                .build();
    }

    /**
     * RedisTemplate Bean
     * [설계 결정]
     * - Key : StringRedisSerializer -> 사람이 읽을 수 있는 형태로 저장 (디버깅 용이)
     * - Value : GenericJacksonJsonRedisSerializer -> JSON 형태로 직렬화하여 타입 안정석 확보
     * [jsonMapper 파라미터 주입]
     * 기존 코드에서는 이 메서드 내부에서 JsonMapper를 직접 생성하여 위에 등록한 @Bean JsonMapper가 Dead Code가 되는 문제가 있었다.
     * 파라미터를 주입받도록 수정하여 DI 원칙을 준수하고 단일 책임을 보장한다.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            JsonMapper jsonMapper // @Bean으로 등록된 JsonMapper를 주입받아 재사용
    ) {
        // Key는 String타입 Value는 Object타입으로 RedisTemplate 설정
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);           // Redis 연결 팩토리 설정

        // Key는 사람이 읽을 수 있는 문자열로 직렬화
        template.setKeySerializer(new StringRedisSerializer());     // 키 직렬화 방식 설정
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value는 Jackson JSON으로 직렬화 (위에서 주입받은 jsonMapper 재사용)
        GenericJacksonJsonRedisSerializer valueSerializer =
                new GenericJacksonJsonRedisSerializer(jsonMapper);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        return template;
    }

    /**
     * Redis Pub/Sub 메시지 리스너 컨테이너 Bean
     * [구독 채널 목록]
     * - ChannelTopic("/topic/chat/global") : 전체 채팅 채널 (정확한 채널명 일치)
     * - PatternTopic("/topic/lobby/*")     : 로비별 채팅 채널 (와일드카드 패턴 매칭)
     * TODO: 채널 경로가 현재 하드코딩되어 있습니다.
     *      Commit #2(상수 클래스 중앙화) 작업 시 StompDestinations 상수로 교체 예정
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSubscriber redisSubscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Java 21 가상 스레드 기반 Executor로 메시지 처리 (플랫폼 스레드 풀 고갈 방지)
        container.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // 전체 채팅 채널 구독 (정확한 채널명 매칭)
        container.addMessageListener(redisSubscriber,
                new ChannelTopic("/topic/chat/global"));

        // 로비 채팅 채널 구독 (패턴 매칭으로 모든 로비 채널을 단일 리스너로 처리)
        container.addMessageListener(redisSubscriber,
                new PatternTopic("/topic/lobby/*"));

        return container;
    }
}

