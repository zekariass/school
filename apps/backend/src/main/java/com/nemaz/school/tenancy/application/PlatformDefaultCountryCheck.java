package com.nemaz.school.tenancy.application;

import com.nemaz.school.tenancy.CountryContext;
import com.nemaz.school.tenancy.CountryResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Fails the start when the platform default country is missing or not ENABLED, with a message that says which setting to fix.
 * Code has no country fallback (docs/Spec_Review.md D42), so a deployment without a valid default must not come up and fail
 * later at the first phone sign-in. Runs before the other runners (the bootstrap administrator's phone needs it).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PlatformDefaultCountryCheck implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PlatformDefaultCountryCheck.class);

	private final CountryResolver resolver;

	PlatformDefaultCountryCheck(CountryResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void run(ApplicationArguments args) {
		CountryContext country = this.resolver.platformDefault();
		log.info("Platform default country: {} ({}, {})", country.code(), country.zone(), country.phoneCountryCode());
	}

}
