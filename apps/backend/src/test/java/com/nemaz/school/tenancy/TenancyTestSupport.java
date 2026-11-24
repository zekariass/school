package com.nemaz.school.tenancy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nemaz.school.tenancy.application.OrganizationService;
import com.nemaz.school.tenancy.application.OrganizationService.CreateOrganization;
import com.nemaz.school.tenancy.application.SchoolConfigurationCache;
import com.nemaz.school.tenancy.application.SchoolService;
import com.nemaz.school.tenancy.application.SchoolService.CreateSchool;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixtures for tenancy tests. All integration tests share one database, so tests
 * create their own organizations and schools (unique slugs) and remove every setting and
 * feature override they leave behind: overrides at platform or country level would
 * otherwise change what other tests resolve.
 */
public abstract class TenancyTestSupport {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected SchoolService schoolService;

	@Autowired
	protected OrganizationService organizationService;

	@Autowired
	protected CacheManager caches;

	@Autowired
	protected JsonMapper json;

	@AfterEach
	void removeConfigurationOverrides() {
		this.jdbc.update("delete from settings");
		this.jdbc.update("delete from feature_overrides");
		this.caches.getCache(SchoolConfigurationCache.CACHE_NAME).clear();
	}

	protected static String uniqueSlug(String prefix) {
		return prefix + "-" + System.nanoTime() % 1_000_000_000L + "-" + SEQUENCE.incrementAndGet();
	}

	protected UUID newOrganization(String countryCode) {
		return this.organizationService
			.create(new CreateOrganization(countryCode, "Org " + SEQUENCE.incrementAndGet(), null, uniqueSlug("org")))
			.getId();
	}

	protected UUID newSchool(UUID organizationId) {
		return this.schoolService
			.create(new CreateSchool("ET", organizationId, "School " + SEQUENCE.incrementAndGet(), null,
					uniqueSlug("school"), null, null, null, null, null, null, null, null, null, null, null))
			.school()
			.getId();
	}

	protected UUID newSchool() {
		return newSchool(null);
	}

}
