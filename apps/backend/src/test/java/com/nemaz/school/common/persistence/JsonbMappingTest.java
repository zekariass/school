package com.nemaz.school.common.persistence;

import java.util.Map;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.reference.domain.AdministrativeArea;
import com.nemaz.school.reference.domain.Country;
import com.nemaz.school.reference.infrastructure.AdministrativeAreaRepository;
import com.nemaz.school.reference.infrastructure.CountryRepository;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.domain.Setting;
import com.nemaz.school.tenancy.infrastructure.SettingRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves how {@code jsonb} columns are mapped: Hibernate 7.4 uses its Jackson 3 format
 * mapper, so values are stored as real JSON objects/values (not as a quoted JSON string)
 * and come back typed.
 */
@IntegrationTest
class JsonbMappingTest {

	@Autowired
	CountryRepository countries;

	@Autowired
	AdministrativeAreaRepository areas;

	@Autowired
	SettingRepository settings;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JsonMapper json;

	@Test
	void readsSeededNameI18nAndJsonNodeColumns() {
		Country et = this.tx.execute((status) -> this.countries.findById("ET").orElseThrow());
		assertThat(et.getNameI18n()).containsEntry("en", "Ethiopia").containsEntry("am", "ኢትዮጵያ");
		assertThat(et.getAddressFormat().get("levels").get(0).get("labels").get("am").asString()).isEqualTo("ክልል");
		assertThat(et.getNameFormat().get("family_name").get("en").asString()).isEqualTo("Grandfather's name");
	}

	@Test
	void writesNameI18nAsAJsonbObject() {
		AdministrativeArea saved = this.areas.saveAndFlush(
				new AdministrativeArea("KE", null, 1, "JSONB-TEST", Map.of("en", "Test county", "am", "የሙከራ ካውንቲ")));
		try {
			assertThat(saved.getId()).isNotNull();
			assertThat(saved.getId().version()).isEqualTo(7);
			assertThat(this.jdbc.queryForObject("select jsonb_typeof(name_i18n) from administrative_areas where id = ?",
					String.class, saved.getId()))
				.isEqualTo("object");
			assertThat(this.jdbc.queryForObject("select name_i18n ->> 'am' from administrative_areas where id = ?",
					String.class, saved.getId()))
				.isEqualTo("የሙከራ ካውንቲ");
			AdministrativeArea reloaded = this.tx
				.execute((status) -> this.areas.findById(saved.getId()).orElseThrow());
			assertThat(reloaded.getNameI18n()).containsEntry("en", "Test county");
		}
		finally {
			this.areas.deleteById(saved.getId());
		}
	}

	@Test
	void roundTripsEveryJsonValueKindThroughJsonNode() {
		JsonNode[] values = { this.json.readTree("true"), this.json.readTree("42"), this.json.readTree("\"text\""),
				this.json.readTree("[\"IMAGE\",\"VIDEO\"]"), this.json.readTree("{\"start\":\"21:00\",\"end\":\"06:00\"}"),
				this.json.nullNode() };
		String[] types = { "boolean", "number", "string", "array", "object", "null" };
		for (int i = 0; i < values.length; i++) {
			Setting saved = this.settings
				.saveAndFlush(new Setting("engagement.badges.enabled", SettingScope.platform(), values[i], null));
			try {
				assertThat(this.jdbc.queryForObject("select jsonb_typeof(value) from settings where id = ?",
						String.class, saved.getId()))
					.as("stored type of %s", values[i])
					.isEqualTo(types[i]);
				Setting reloaded = this.tx.execute((status) -> this.settings.findById(saved.getId()).orElseThrow());
				assertThat(reloaded.getValue()).as("value of %s", values[i]).isEqualTo(values[i]);
			}
			finally {
				this.settings.deleteById(saved.getId());
			}
		}
	}

}
