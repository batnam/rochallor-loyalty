package io.github.batnam.loyalty.campaign.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock {@link LockProvider} backed by the {@code shedlock} table in loyalty-campaign RDS (L3 §5).
 * campaign runs multi-pod, so every {@code @Scheduled} job (Drawing Scheduler + Outbox Relay) is wrapped
 * in {@code @SchedulerLock} — exactly one pod runs each tick; the rest skip. This is the application-level
 * guarantee that a Drawing is selected once; the {@code SELECT … FOR UPDATE} in Winner Selection is the
 * second line of defence at the row level. {@code lock_until} lets a crashed pod release by timeout.
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
