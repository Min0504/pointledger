package com.pointledger.batch;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 공통 인프라. 락 저장소가 DB인 이유: 배치의 진실(로트·원장)이 이미
 * PostgreSQL에 있는데 락만 Redis에 두면 장애 도메인이 하나 늘어난다 —
 * 같은 DB면 "DB가 살아있다 == 락이 동작한다"로 조건이 붙는다 (기획서 §10).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class BatchInfraConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // 인스턴스 시계가 아니라 DB now() 기준 — 시계 오차 내성
                .build());
    }

    /** 테스트에서는 스케줄 트리거 자체를 끈다 — Job은 명시적 기동으로만 검증 */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "pointledger.batch.scheduling-enabled",
            havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
