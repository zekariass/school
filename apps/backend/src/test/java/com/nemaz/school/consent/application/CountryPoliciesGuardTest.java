package com.nemaz.school.consent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.CountryPolicies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D42: a country is enabled only with a published TERMS and a published PRIVACY of its own. Kenya is a draft country in the
 * seeds, so it is the country these tests switch on and put back.
 */
@IntegrationTest
class CountryPoliciesGuardTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	CountryPoliciesCheck check;

	@AfterEach
	void restore() {
		CountryPolicies.disable(this.jdbc, "KE");
	}

	@Test
	void aCountryWithoutTermsAndPrivacyCannotBeEnabled() {
		assertThatThrownBy(() -> this.jdbc.update("update countries set status = 'ENABLED' where code = 'KE'"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("country.policies_missing")
			.hasMessageContaining("PRIVACY, TERMS");
		assertThat(status()).isEqualTo("DRAFT");
	}

	@Test
	void oneOfTheTwoIsNotEnough() {
		CountryPolicies.publishBasics(this.jdbc, "KE");
		this.jdbc.update("delete from policy_versions where policy_document_id in"
				+ " (select id from policy_documents where country_code = 'KE' and policy_type = 'PRIVACY')");
		assertThatThrownBy(() -> this.jdbc.update("update countries set status = 'ENABLED' where code = 'KE'"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("has no published PRIVACY");
	}

	@Test
	void aDraftOrFutureVersionDoesNotCount() {
		CountryPolicies.publishBasics(this.jdbc, "KE");
		this.jdbc.update("update policy_versions set published_at = null where policy_document_id in"
				+ " (select id from policy_documents where country_code = 'KE' and policy_type = 'TERMS')");
		this.jdbc.update("update policy_versions set effective_from = now() + interval '7 days' where policy_document_id in"
				+ " (select id from policy_documents where country_code = 'KE' and policy_type = 'PRIVACY')");
		assertThatThrownBy(() -> this.jdbc.update("update countries set status = 'ENABLED' where code = 'KE'"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("PRIVACY, TERMS");
	}

	@Test
	void theMediaAndAccountConsentsAreNotRequired() {
		CountryPolicies.enable(this.jdbc, "KE");
		assertThat(status()).isEqualTo("ENABLED");
		assertThat(this.jdbc.queryForObject("select count(*) from policy_documents where country_code = 'KE'"
				+ " and policy_type in ('MEDIA_PUBLICATION', 'ACCOUNT_PARTICIPATION')", Integer.class)).isZero();
	}

	@Test
	void otherStatusChangesAreNotBlocked() {
		CountryPolicies.enable(this.jdbc, "KE");
		this.jdbc.update("update countries set status = 'DISABLED' where code = 'KE'");
		this.jdbc.update("update countries set status = 'DRAFT' where code = 'KE'");
		assertThat(status()).isEqualTo("DRAFT");
	}

	@Test
	void theStartupCheckNamesAnEnabledCountryThatLostItsText() {
		assertThat(this.check.problems()).as("only Ethiopia is enabled and it has its seeded texts").isEmpty();
		CountryPolicies.enable(this.jdbc, "KE");
		assertThat(this.check.problems()).isEmpty();
		this.jdbc.update("delete from policy_versions where policy_document_id in"
				+ " (select id from policy_documents where country_code = 'KE' and policy_type = 'TERMS')");
		assertThat(this.check.problems()).containsExactly("KE (TERMS)");
		assertThatThrownBy(() -> this.check.run(null)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KE (TERMS)");
	}

	private String status() {
		return this.jdbc.queryForObject("select status from countries where code = 'KE'", String.class);
	}

}
