package com.nemaz.school;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Enforces module boundaries (Implementation Plan M0.3): no cycles between modules, and
 * modules use each other only through their top-level (API) package, never another
 * module's internal subpackages. Also writes module diagrams to target/spring-modulith-docs.
 */
class ModularityTest {

	static final ApplicationModules modules = ApplicationModules.of(SchoolBackendApplication.class);

	@Test
	void verifiesModuleBoundaries() {
		modules.verify();
	}

	@Test
	void writesModuleDocumentation() {
		new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
	}

}
