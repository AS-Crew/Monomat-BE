package io.github.ascrew.monomatbe.global.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * dev 환경에서 부분 적용된 Flyway 마이그레이션(failed 상태)을 자동으로 복구한다.
 *
 * repair()는 flyway_schema_history의 실패 항목을 제거하므로,
 * 이후 migrate()가 해당 버전을 idempotent 버전으로 재실행할 수 있다.
 * prod에는 적용하지 않는다 — repair는 의도적인 판단 하에 수행해야 한다.
 */
@Configuration
@Profile("dev")
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy repairAndMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
