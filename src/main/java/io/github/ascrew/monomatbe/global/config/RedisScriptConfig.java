package io.github.ascrew.monomatbe.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis Lua 스크립트를 관리하는 설정 클래스
 *
 * [설계 의도]
 * Lua 스크립트를 매 요청마다 파일에서 읽지 않고, Spring Boot 가동 시 Bean으로 등록하여 재사용한다.
 *
 * [관리 대상]
 * - create_lobby.lua : 로비 생성 원자 처리
 * - leave_lobby.lua : 로비 퇴장/방장 위임/폭파 원자 처리
 * - enter_lobby.lua : 로비 입장 상태 저장 원자 처리
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

    @Bean
    public RedisScript<String> createLobbyScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/create_lobby.lua"));
        redisScript.setResultType(String.class);
        return redisScript;
    }

    /**
     * 로비 입장 처리 Lua 스크립트
     *
     * [처리 내용]
     * - 로비 존재 여부 확인
     * - participants Set 저장
     * - order List 저장
     * - wsSessionId -> lobbyCode/userIdentifier 매핑 저장
     * - 중복 구독 시 participants/order 중복 저장 방지
     */
    @Bean
    public RedisScript<String> enterLobbyScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/enter_lobby.lua"));
        redisScript.setResultType(String.class);
        return redisScript;
    }

    /**
     * 로비 유저 강퇴 처리 Lua 스크립트
     *
     * [처리 내용]
     * - 로비 존재 여부 확인
     * - 방장 권한 검증
     * - 자기 자신 강퇴 방지
     * - 강퇴 대상 참여 여부 확인
     * - participants Set에서 대상 제거
     * - order List에서 대상 제거
     * - 대상자의 로비 WebSocket 세션 매핑 제거
     */
    @Bean
    public RedisScript<String> kickLobbyScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("scripts/kick_lobby.lua"));
        redisScript.setResultType(String.class);
        return redisScript;
    }
}