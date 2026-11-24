package com.nemaz.school.system.scheduling;

import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The application's default executor for {@code @Async} (module event listeners) and for
 * background work such as sending sign-in codes. Declared explicitly because the STOMP
 * configuration registers its own channel executors, which makes Spring Boot's auto-configured
 * {@code applicationTaskExecutor} back off.
 */
@Configuration(proxyBeanMethods = false)
class AsyncConfig {

	@Bean(name = { "applicationTaskExecutor", "taskExecutor" })
	ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
		return builder.build();
	}

}
