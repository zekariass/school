package com.nemaz.school.people;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.support.Personas;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The behaviour settings of M3a: defaults and bounds. */
@IntegrationTest
class SchoolSettingsTest {

	@Autowired
	SettingsService settings;

	@Autowired
	Personas personas;

	@Autowired
	JsonMapper json;

	@Test
	void defaultsAndBounds() {
		UUID school = this.personas.fixture().schoolA();
		SettingScope scope = SettingScope.school(school);
		assertThat(this.settings.getBoolean("classes.teachers_can_create", scope)).isTrue();
		assertThat(this.settings.getInt("students.login_code.ttl_days", scope)).contains(365);
		assertThat(this.settings.getBoolean("students.password_login.enabled", scope)).isFalse();
		UUID actor = this.personas.platformAdmin().userId();
		assertThatThrownBy(() -> this.settings.set("students.login_code.ttl_days", scope, this.json.valueToTree(731), actor))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> this.settings.set("students.login_code.ttl_days", scope, this.json.valueToTree(0), actor))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> this.settings.set("students.password_login.enabled", scope, this.json.valueToTree("yes"), actor))
			.isInstanceOf(ApiException.class);
	}

}
