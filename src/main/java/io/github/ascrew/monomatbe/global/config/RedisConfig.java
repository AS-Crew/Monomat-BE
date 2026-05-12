/*
 * 애플리케이션의 Redis 연결 및 실시간 통신 환경을 구성하는 설정 클래스.
 *
 * [주요 설정]
 * - Connection  : 비동기 처리에 특화된 Lettuce 클라이언트로 Redis 연결
 *                 (Virtual Thread 핀닝 방지를 위해 Jedis 대신 Lettuce 사용)
 * - Serializer  : JsonMapper (Jackson 3.x)를 단일 @Bean으로 관리하여
 *                 RedisTemplate의 직렬화 설정을 중앙화
 *                 activateDefaultTyping(NON_FINAL) 설정으로 역직렬화 시 타입 정보 보존
 * - Pub/Sub     : 실시간 채팅 및 상태 동기화를 위한 MessageListenerContainer 활성화
 *
 * [리팩토링 변경 사항]
 * - redisTemplate() 메서드에 JsonMapper 파라미터 추가
 *   기존에 @Bean jsonMapper()를 등록해 두고도 redisTemplate()에서 주입받지 않아
 *   Dead Code 상태였던 문제를 수정합니다.
 *   파라미터로 주입받도록 변경하여 화이트리스트 보안 정책이 실제로 적용되도록 합니다.
 *
 * - Pub/Sub 전용 JsonMapper 추가
 *   일반 RedisTemplate은 타입 정보를 포함하는 JsonMapper를 사용하고,
 *   Pub/Sub 메시지는 프론트 계약을 위해 타입 정보 없는 순수 JSON JsonMapper를 사용합니다.
 */
package io.github.ascrew.monomatbe.global.config;

import io.github.ascrew.monomatbe.global.constant.StompDestinations;
import io.github.ascrew.monomatbe.global.redis.RedisSubscriber;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Redis 연결 팩토리 Bean.
     *
     * [Lettuce를 사용하는 이유]
     * Jedis는 동기 블로킹 방식으로 Virtual Thread를 핀닝(Pinning)할 수 있습니다.
     * Lettuce는 Netty 기반 비동기 드라이버로, Virtual Thread와 함께 사용해도
     * 캐리어 스레드를 점유하지 않아 성능 저하 없이 동작합니다.
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
     * Jackson 3.x JsonMapper Bean.
     *
     * [단일 Bean으로 관리하는 이유]
     * 역직렬화 허용 타입 화이트리스트 정책을 이 Bean 하나에서 중앙 관리합니다.
     * 정책 변경 시 이 메서드만 수정하면 JsonMapper를 사용하는 모든 곳에 반영됩니다.
     *
     * [activateDefaultTyping 설정]
     * JSON에 @class 필드를 포함시켜 역직렬화 시 타입 정보를 보존합니다.
     * NON_FINAL 클래스에만 적용하여 불필요한 오버헤드를 줄입니다.
     *
     * [보안 — 화이트리스트]
     * 허용 패키지를 명시적으로 제한하여 역직렬화 공격(Deserialization Attack)을 방지합니다.
     */
    @Bean
    public JsonMapper jsonMapper() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("io.github.ascrew.monomatbe.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .build();

        return JsonMapper.builder()
                .activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL)
                .build();
    }

    /**
     * Redis Pub/Sub 전용 JsonMapper.
     *
     * RedisTemplate<String, Object>에서 사용하는 jsonMapper()는
     * activateDefaultTyping 설정으로 타입 정보를 JSON에 포함합니다.
     *
     * 이 설정은 Redis Hash/Value에 객체 타입을 보존해야 할 때는 유용하지만,
     * WebSocket 클라이언트로 전달되는 Pub/Sub 메시지에는 부적합합니다.
     *
     * 따라서 Pub/Sub 메시지는 클래스 타입 정보 없이 순수 JSON 형태로 직렬화하기 위해
     * 별도의 JsonMapper를 사용합니다.
     */
    @Bean
    @Primary
    public JsonMapper pubSubJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * RedisTemplate Bean.
     *
     * [직렬화 전략]
     * - Key      : StringRedisSerializer             → 사람이 읽을 수 있는 문자열
     * - Hash Key : StringRedisSerializer             → Hash 필드명도 문자열 유지
     * - Value    : GenericJacksonJsonRedisSerializer → JSON 직렬화, 타입 정보 포함
     * - Hash Val : GenericJacksonJsonRedisSerializer → Hash 값 직렬화, 타입 정보 포함
     *
     * [중요]
     * RedisConfig에는 JsonMapper 타입 Bean이 2개 있습니다.
     * - jsonMapper       : 일반 RedisTemplate용, 타입 정보 포함
     * - pubSubJsonMapper : Pub/Sub용, 타입 정보 미포함
     *
     * 따라서 redisTemplate()에는 @Qualifier("jsonMapper")를 명시하여
     * 일반 RedisTemplate용 Mapper가 주입되도록 고정합니다.
     *
     * @param connectionFactory Redis 연결 팩토리
     * @param jsonMapper 일반 RedisTemplate 직렬화용 JsonMapper
     * @return RedisTemplate<String, Object>
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            @Qualifier("jsonMapper") JsonMapper jsonMapper
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        GenericJacksonJsonRedisSerializer valueSerializer =
                new GenericJacksonJsonRedisSerializer(jsonMapper);

        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        return template;
    }

    /**
     * Redis Pub/Sub 메시지 리스너 컨테이너 Bean.
     *
     * [구독 채널 목록]
     * - ChannelTopic("/topic/chat/global") : 전체 채팅 채널
     * - PatternTopic("/topic/lobby/*")     : 로비별 채팅 채널
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