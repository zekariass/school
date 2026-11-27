package com.nemaz.school.moderation.application;

import java.util.Locale;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.moderation.application.ModerationModels.EmergencyInfoView;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-country emergency text of the reporting screens (docs/Safeguarding_Rules.md decision 10): configurable data, in the
 * caller's language (English when the language has no text). The text of a placeholder country is flagged
 * {@code reviewed: false} until the owner supplies the final wording and numbers. The country is always asked for explicitly
 * and a country without a row answers 404 {@code safety.emergency_info_not_found}: safety numbers of another country are never
 * served instead (they would send a child to the wrong helpline).
 */
@Service
public class EmergencyInfoService {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	EmergencyInfoService(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public EmergencyInfoView forCountry(String country) {
		if (country == null || country.length() != 2) {
			throw ApiException.badRequest("safety.country_invalid");
		}
		String code = country.toUpperCase(Locale.ROOT);
		record Row(String text, boolean reviewed) {
		}
		Row row = this.jdbc.sql("select text_i18n::text as text, reviewed from emergency_information where country_code = :c")
			.param("c", code)
			.query((rs, i) -> new Row(rs.getString("text"), rs.getBoolean("reviewed")))
			.optional()
			.orElseThrow(() -> ApiException.notFound("safety.emergency_info_not_found"));
		JsonNode texts = this.json.readTree(row.text());
		String language = LocaleContextHolder.getLocale().getLanguage();
		JsonNode chosen = texts.has(language) ? texts.get(language) : texts.get("en");
		return new EmergencyInfoView(code, (chosen != null) ? chosen.asString() : "", row.reviewed());
	}

}
