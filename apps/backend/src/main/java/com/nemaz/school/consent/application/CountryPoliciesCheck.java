package com.nemaz.school.consent.application;

import java.util.ArrayList;
import java.util.List;

import com.nemaz.school.reference.CountryInfo;
import com.nemaz.school.reference.ReferenceData;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fails the start when an ENABLED country has no published TERMS or PRIVACY text (docs/Spec_Review.md D42). The database
 * trigger {@code countries_require_policies} stops a country from being switched to ENABLED without them; this covers a
 * country that was inserted already enabled (a seed) or whose documents were removed afterwards. Code never borrows another
 * country's legal text, so such a deployment must not come up.
 */
@Component
class CountryPoliciesCheck implements ApplicationRunner {

	/** The documents every enabled country needs (the owner's decision: these two are enough for now). */
	static final List<String> REQUIRED = List.of("TERMS", "PRIVACY");

	private final ReferenceData reference;

	private final JdbcClient jdbc;

	CountryPoliciesCheck(ReferenceData reference, JdbcClient jdbc) {
		this.reference = reference;
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		List<String> problems = problems();
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Enabled countries without published legal text: " + String.join("; ", problems)
					+ ". Publish the documents (policy_documents / policy_versions) or set the country's status to DRAFT.");
		}
	}

	/** One entry per enabled country that lacks a required document, e.g. {@code KE (TERMS, PRIVACY)}. */
	List<String> problems() {
		List<String> problems = new ArrayList<>();
		for (CountryInfo country : this.reference.findEnabledCountries()) {
			List<String> missing = new ArrayList<>();
			for (String type : REQUIRED) {
				boolean published = this.jdbc.sql("""
						select exists (select 1 from policy_documents d join policy_versions v on v.policy_document_id = d.id
						               where d.country_code = :c and d.school_id is null and d.policy_type = :t
						                 and v.published_at is not null and v.effective_from <= now())""")
					.param("c", country.code())
					.param("t", type)
					.query(Boolean.class)
					.single();
				if (!published) {
					missing.add(type);
				}
			}
			if (!missing.isEmpty()) {
				problems.add(country.code() + " (" + String.join(", ", missing) + ")");
			}
		}
		return problems;
	}

}
