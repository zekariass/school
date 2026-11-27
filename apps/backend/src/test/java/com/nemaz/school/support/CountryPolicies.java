package com.nemaz.school.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helper for D42: a country can only be ENABLED once it has published TERMS and PRIVACY text (database trigger
 * {@code countries_require_policies}). Tests that switch a draft country such as Kenya on call {@link #enable} and put it
 * back with {@link #disable}.
 */
public final class CountryPolicies {

	private CountryPolicies() {
	}

	/** Publishes a placeholder TERMS and PRIVACY (English) for the country; safe to call again. */
	public static void publishBasics(JdbcTemplate jdbc, String country) {
		for (String type : new String[] { "TERMS", "PRIVACY" }) {
			jdbc.update("""
					insert into policy_documents (policy_type, country_code, title) values (?, ?, ?)
					on conflict (policy_type, country_code) where school_id is null do nothing""", type, country,
					"Test " + type + " " + country);
			jdbc.update("""
					insert into policy_versions (policy_document_id, version_label, language_code, content, effective_from, published_at)
					select d.id, 'test-1', 'en', 'Test wording for ' || d.policy_type, now() - interval '1 day', now() - interval '1 day'
					from policy_documents d where d.policy_type = ? and d.country_code = ? and d.school_id is null
					on conflict on constraint ux_policy_versions_label do nothing""", type, country);
		}
	}

	/** Publishes the two documents and switches the country to ENABLED. */
	public static void enable(JdbcTemplate jdbc, String country) {
		publishBasics(jdbc, country);
		jdbc.update("update countries set status = 'ENABLED' where code = ?", country);
	}

	/** Back to DRAFT and removes every country-level document of the country. */
	public static void disable(JdbcTemplate jdbc, String country) {
		jdbc.update("update countries set status = 'DRAFT' where code = ?", country);
		jdbc.update("delete from policy_versions where policy_document_id in (select id from policy_documents"
				+ " where country_code = ? and school_id is null)", country);
		jdbc.update("delete from policy_documents where country_code = ? and school_id is null", country);
	}

}
