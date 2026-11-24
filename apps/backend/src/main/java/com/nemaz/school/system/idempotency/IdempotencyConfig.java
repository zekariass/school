package com.nemaz.school.system.idempotency;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires Idempotency-Key handling: the buffering filter (after Spring Security) and the MVC interceptor. */
@Configuration(proxyBeanMethods = false)
class IdempotencyConfig implements WebMvcConfigurer {

	private final IdempotencyStore store;

	IdempotencyConfig(IdempotencyStore store) {
		this.store = store;
	}

	@Bean
	FilterRegistrationBean<IdempotencyFilter> idempotencyFilter() {
		FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
				new IdempotencyFilter(this.store));
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10);
		registration.addUrlPatterns("/api/*");
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new IdempotencyInterceptor(this.store)).addPathPatterns("/api/**");
	}

	/** Removes expired keys once an hour. */
	@Component
	static class IdempotencyKeyCleanup {

		private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);

		private final IdempotencyStore store;

		IdempotencyKeyCleanup(IdempotencyStore store) {
			this.store = store;
		}

		@Scheduled(cron = "0 7 * * * *")
		@SchedulerLock(name = "system.idempotency-cleanup")
		void purge() {
			int removed = this.store.purgeExpired();
			if (removed > 0) {
				log.info("Removed {} expired idempotency keys", removed);
			}
		}

	}

}
