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
 */
package io.github.ascrew.monomatbe.global.config;

import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import tools.jackson.databind.json.JsonMapper;
import io.github.ascrew.monomatbe.global.redis.RedisSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
import io.github.ascrew.monomatbe.global.constant.StompDestinations;

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
        // 1. 단일 Redis 서버 설정을 위한 객체 생성
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();

        // 2. IP 및 포트 설정
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);

        // 3. 우분투 서버에서 설정한 비밀번호를 주입
        redisConfig.setPassword(RedisPassword.of(redisPassword));

        // 4. 완성된 설정을 넣어 Factory 반환
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
        // 역직렬화를 허용할 타입을 화이트리스트 방식으로 명시적 제한
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
     * Redis Pub/Sub 전용 JsonMapper
     */
    @Bean
    public JsonMapper pubSubJsonMapper() {
        return JsonMapper.builder().build();
    }

    /**
     * RedisTemplate Bean.
     *
     * [직렬화 전략]
     * - Key   : StringRedisSerializer              → 사람이 읽을 수 있는 문자열 (디버깅 용이)
     * - Value : GenericJacksonJsonRedisSerializer  → JSON 직렬화, 타입 정보(@class) 포함
     *
     * [리팩토링 수정 — JsonMapper 파라미터 주입]
     * 기존 코드는 @Bean jsonMapper()를 등록해 두고도 이 메서드에서 주입받지 않아
     * Dead Code 상태였습니다.
     *
     * 기존: redisTemplate(RedisConnectionFactory connectionFactory)
     *       → 메서드 내부에서 JsonMapper를 직접 새로 생성 (화이트리스트 정책 미적용 위험)
     *
     * 수정: redisTemplate(RedisConnectionFactory connectionFactory, JsonMapper jsonMapper)
     *       → Spring이 위에서 등록한 @Bean jsonMapper()를 주입
     *       → 화이트리스트 보안 정책이 실제 직렬화/역직렬화에 확실히 적용됨
     *
     * @param connectionFactory Redis 연결 팩토리 (redisConnectionFactory Bean 주입)
     * @param jsonMapper        Jackson 3.x 매퍼 (jsonMapper Bean 주입) — Dead Code 수정
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            JsonMapper jsonMapper // [수정] 파라미터 추가 → @Bean jsonMapper() 실제 주입
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key: 문자열 그대로 저장 (예: "lobby:ABC123", "user_status:uuid-xxxx")
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value: JSON 직렬화
        // [수정] 메서드 내부에서 JsonMapper를 새로 생성하지 않고
        //        파라미터로 주입받은 @Bean을 재사용하여 정책 일관성 보장
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
     * - ChannelTopic("/topic/chat/global") : 전체 채팅 채널 (정확한 채널명 일치)
     * - PatternTopic("/topic/lobby/*")     : 로비별 채팅 채널 (와일드카드 패턴 매칭)
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

        // Java 21 Virtual Thread 기반 Executor로 메시지 처리 (플랫폼 스레드 풀 고갈 방지)
        container.setTaskExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // 전체 채팅 채널 구독 (정확한 채널명 매칭)
        container.addMessageListener(
                redisSubscriber,
                new ChannelTopic(StompDestinations.SUBSCRIBE_GLOBAL_CHAT)
        );

        // 로비 채팅 채널 구독 (패턴 매칭으로 모든 로비 채널을 단일 리스너로 처리)
        container.addMessageListener(
                redisSubscriber,
                new PatternTopic(StompDestinations.SUBSCRIBE_LOBBY_PATTERN)
        );

        return container;
    }
}
