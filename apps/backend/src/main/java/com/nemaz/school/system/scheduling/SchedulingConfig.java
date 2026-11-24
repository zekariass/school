package com.nemaz.school.system.scheduling;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Recurring jobs use {@code @Scheduled} + {@code @SchedulerLock} so that with several
 * backend instances each run happens once. Lock times use the database clock
 * ({@code shedlock} table, migration V0_02) to avoid skew between instances.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "school.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

	@Bean
	LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
			.withJdbcTemplate(new JdbcTemplate(dataSource))
			.withTableName("shedlock")
			.usingDbTime()
			.build());
	}

}
