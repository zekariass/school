package com.nemaz.school.system.startup;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fails the start of a deployed profile ({@code staging}, {@code prod}) before any bean, connection
 * or web server exists, with one message that lists every configuration problem
 * ({@link DeploymentConfigValidator}). Runs after the configuration files are loaded, so the values
 * checked are the resolved ones. Registered in {@code META-INF/spring.factories}.
 */
public class DeploymentConfigPostProcessor implements EnvironmentPostProcessor, Ordered {

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		List<String> problems = DeploymentConfigValidator.validate(environment);
		if (!problems.isEmpty()) {
			String profiles = Arrays.stream(environment.getActiveProfiles())
				.filter((profile) -> !"deployed".equals(profile))
				.collect(Collectors.joining(", "));
			throw new DeploymentConfigurationException((profiles.isEmpty()) ? "default" : profiles, problems);
		}
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
