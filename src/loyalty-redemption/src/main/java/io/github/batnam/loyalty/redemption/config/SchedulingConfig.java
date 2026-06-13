package io.github.batnam.loyalty.redemption.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock {@link LockProvider} backed by the {@code shedlock} table in loyalty-redemption RDS (L3 §4).
 * redemption runs multi-pod, so every {@code @Scheduled} job (Outbox Relay) is wrapped in
 * {@code @SchedulerLock} — exactly one pod runs each tick; the rest skip. {@code lock_until} lets a
 * crashed pod release by timeout without operator intervention.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()   // use Postgres clock, not per-pod clock
                        .build());
    }
}
