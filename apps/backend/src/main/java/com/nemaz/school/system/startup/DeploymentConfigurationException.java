package com.nemaz.school.system.startup;

import java.util.List;

/** Thrown at startup when a deployed profile is misconfigured; carries every problem found. */
public class DeploymentConfigurationException extends RuntimeException {

	private final List<String> problems;

	private final String profiles;

	public DeploymentConfigurationException(String profiles, List<String> problems) {
		super(summary(profiles, problems));
		this.problems = List.copyOf(problems);
		this.profiles = profiles;
	}

	public List<String> problems() {
		return this.problems;
	}

	public String profiles() {
		return this.profiles;
	}

	static String summary(String profiles, List<String> problems) {
		StringBuilder text = new StringBuilder("Invalid configuration for profile(s) [").append(profiles)
			.append("]: ")
			.append(problems.size())
			.append(problems.size() == 1 ? " problem" : " problems");
		int number = 0;
		for (String problem : problems) {
			text.append("\n  ").append(++number).append(". ").append(problem);
		}
		return text.toString();
	}

}
