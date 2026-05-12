package io.github.ascrew.monomatbe.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Scheduling 활성화 설정.
 *
 * [사용 목적]
 * Redis-DB 상태 불일치 재처리 작업처럼 주기적으로 실행되어야 하는 백그라운드 보정 루틴을 실행한다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}