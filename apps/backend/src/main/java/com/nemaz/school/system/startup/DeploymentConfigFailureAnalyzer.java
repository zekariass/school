package com.nemaz.school.system.startup;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Prints a {@link DeploymentConfigurationException} as one readable block instead of a stack trace. */
class DeploymentConfigFailureAnalyzer extends AbstractFailureAnalyzer<DeploymentConfigurationException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, DeploymentConfigurationException cause) {
		return new FailureAnalysis(cause.getMessage(),
				"Set the environment variables named above (secret values are never printed) and start again. "
						+ "See docs/ops/Runbook.md and infra/deploy/.env.example.",
				cause);
	}

}
