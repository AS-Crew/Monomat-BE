package io.github.ascrew.monomatbe.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis Lua 스크립트를 관리하는 설정 클래스
 * 매번 스크립트 파일을 읽지 않고, Spring Boot 기동 시 Bean으로 등록하여 캐싱
 * 네트워크 비용 절감 및 스크립트 파싱 오버헤드를 없애기 위한 조치임
 */

@Configuration
public class RedisScriptConfig {

    @Bean
    public RedisScript<String> leaveLobbyScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/leave_lobby.lua"));
        redisScript.setResultType(String.class);
        return redisScript;
    }
}