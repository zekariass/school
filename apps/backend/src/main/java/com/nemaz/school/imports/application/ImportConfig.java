package com.nemaz.school.imports.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The small pool file parsing runs on: four at a time, eight waiting, then {@code import.busy}. */
@Configuration(proxyBeanMethods = false)
class ImportConfig {

	@Bean(destroyMethod = "shutdownNow")
	ThreadPoolExecutor importParserPool() {
		java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
		return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), (r) -> {
			Thread t = new Thread(r, "import-parser-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

}
