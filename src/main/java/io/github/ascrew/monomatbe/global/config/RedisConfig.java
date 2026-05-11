/*
 * 애플리케이션의 Redis 연결 및 실시간 통신 환경을 구성하는 설정 클래스.
 *
 * [주요 설정]
 * - Connection : 비동기 처리에 특화된 Lettuce 클라이언트로 Redis 연결
 *                (Virtual Thread 핀닝 방지를 위해 Jedis 대신 Lettuce 사용)
 * - Serializer : RedisTemplate 내부에서만 타입 정보 포함 JsonMapper를 사용
 * - Pub/Sub    : 실시간 채팅 및 상태 동기화를 위한 MessageListenerContainer 활성화
 *
 * [중요 변경 사항]
 * - Redis 직렬화용 JsonMapper를 Spring Bean으로 노출하지 않습니다.
 *   activateDefaultTyping이 적용된 JsonMapper가 HTTP 응답 직렬화에 사용되면
 *   GET /api/lobbies 같은 REST 응답에 Java 클래스 타입 정보가 포함될 수 있습니다.
 *
 * - RedisTemplate은 내부 private 메서드로 생성한 Redis 전용 JsonMapper를 사용합니다.
 *   따라서 Redis 역직렬화에 필요한 타입 정보는 유지하면서,
 *   HTTP 응답 JSON 계약은 순수 DTO 형태로 분리합니다.
 *
 * - Pub/Sub 메시지는 프론트 계약을 위해 타입 정보 없는 순수 JSON JsonMapper를 사용합니다.
 */
package io.github.ascrew.monomatbe.global.config;

import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.redis.RedisSubscriber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.concurrent.Executors;

@Configuration
public class RedisConfig {

    /*
     * Redis 역직렬화 허용 패키지 목록입니다.
     *
     * 문자열 리터럴을 직접 흩뿌리지 않고 상수로 관리하여
     * 허용 범위 변경 시 수정 위치를 한 곳으로 제한합니다.
     */
    private static final String ALLOWED_APPLICATION_PACKAGE = "io.github.ascrew.monomatbe.";
    private static final String ALLOWED_JAVA_UTIL_PACKAGE = "java.util.";
    private static final String ALLOWED_JAVA_LANG_PACKAGE = "java.lang.";

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Redis 연결 팩토리 Bean.
     *
     * [Lettuce 사용 이유]
     * Jedis는 동기 블로킹 방식으로 Virtual Thread를 핀닝할 수 있습니다.
     * Lettuce는 Netty 기반 비동기 드라이버이므로 Virtual Thread 환경에서
     * 캐리어 스레드 점유 위험을 줄일 수 있습니다.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();

        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        redisConfig.setPassword(RedisPassword.of(redisPassword));

        return new LettuceConnectionFactory(redisConfig);
    }

    /**
     * Redis Pub/Sub 전용 JsonMapper.
     *
     * [역할]
     * WebSocket 클라이언트로 전달되는 Pub/Sub 메시지는
     * Java 클래스 타입 정보가 포함되면 안 됩니다.
     *
     * 따라서 이 Mapper는 activateDefaultTyping을 적용하지 않고,
     * 순수 JSON 직렬화에만 사용합니다.
     */
    @Bean
    public JsonMapper pubSubJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * RedisTemplate Bean.
     *
     * [직렬화 전략]
     * - Key       : StringRedisSerializer
     * - Hash Key  : StringRedisSerializer
     * - Value     : GenericJacksonJsonRedisSerializer
     * - Hash Value: GenericJacksonJsonRedisSerializer
     *
     * [중요]
     * 타입 정보가 포함된 JsonMapper는 RedisTemplate 내부에서만 생성하여 사용합니다.
     * 이를 Spring Bean으로 등록하지 않아 HTTP 응답 직렬화에 영향을 주지 않도록 합니다.
     *
     * @param connectionFactory Redis 연결 팩토리
     * @return RedisTemplate<String, Object>
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        JsonMapper redisJsonMapper = createRedisJsonMapper();

        GenericJacksonJsonRedisSerializer valueSerializer =
                new GenericJacksonJsonRedisSerializer(redisJsonMapper);

        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        return template;
    }

    /**
     * Redis 직렬화 전용 JsonMapper를 생성합니다.
     *
     * [Bean으로 등록하지 않는 이유]
     * activateDefaultTyping이 적용된 JsonMapper가 Spring MVC의 HTTP 응답 직렬화에 사용되면,
     * 응답 JSON에 java.util.ArrayList 또는 DTO 클래스명이 포함될 수 있습니다.
     *
     * [보안]
     * 역직렬화 허용 타입은 프로젝트 패키지와 필요한 JDK 기본 패키지로 제한합니다.
     *
     * @return RedisTemplate 내부에서만 사용하는 타입 정보 포함 JsonMapper
     */
    private JsonMapper createRedisJsonMapper() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(ALLOWED_APPLICATION_PACKAGE)
                .allowIfSubType(ALLOWED_JAVA_UTIL_PACKAGE)
                .allowIfSubType(ALLOWED_JAVA_LANG_PACKAGE)
                .build();

        return JsonMapper.builder()
                .activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL)
                .build();
    }

    /**
     * Redis Pub/Sub 메시지 리스너 컨테이너 Bean.
     *
     * [구독 채널]
     * - /topic/chat/global : 전체 채팅 채널
     * - /topic/lobby/*     : 로비별 채팅 채널
     *
     * [Virtual Thread Executor]
     * 메시지 처리 스레드를 Virtual Thread로 설정하여
     * 다수의 동시 메시지 처리 시 플랫폼 스레드 풀 고갈을 방지합니다.
     */
    @Bean
    @Profile("!test")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSubscriber redisSubscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());

        container.addMessageListener(
                redisSubscriber,
                new ChannelTopic(StompDestinations.SUBSCRIBE_GLOBAL_CHAT)
        );

        container.addMessageListener(
                redisSubscriber,
                new PatternTopic(StompDestinations.SUBSCRIBE_LOBBY_PATTERN)
        );

        return container;
    }
}