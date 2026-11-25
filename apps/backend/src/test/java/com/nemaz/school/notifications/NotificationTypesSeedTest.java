package com.nemaz.school.notifications;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.authorization.RoleCodes;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.support.Personas;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class NotificationTypesSeedTest extends NotificationTestSupport {

	@Autowired
	MessageSource messages;

	@Autowired
	JdbcTemplate db;

	@Test
	void everyTypeHasItsTextsInBothLanguages() {
		List<String> codes = this.db.queryForList("select code from notification_types order by code", String.class);
		assertThat(codes).contains("ATTENDANCE_MARKED", "NEW_MESSAGE", "POINT_AWARDED", "NEW_POST", "NEW_STORY",
				"POST_APPROVED", "ANNOUNCEMENT_PUBLISHED", "ACK_REQUESTED", "PORTFOLIO_REVIEWED", "IMPORT_COMPLETED",
				"CONSENT_REQUESTED", "GUARDIAN_LINK_APPROVED", "UPCOMING_EVENT", "ROLE_GRANTED", "TEST_NOTIFICATION");
		Locale am = Locale.forLanguageTag("am");
		for (String code : codes) {
			String key = "notification." + code.toLowerCase();
			for (String suffix : List.of(".title", ".body", ".label")) {
				String en = this.messages.getMessage(key + suffix, null, Locale.ENGLISH);
				String amharic = this.messages.getMessage(key + suffix, null, am);
				assertThat(en).as(key + suffix).isNotBlank();
				assertThat(amharic).as(key + suffix + " (am)").isNotBlank();
			}
		}
		for (String category : this.db.queryForList("select distinct category from notification_types", String.class)) {
			assertThat(this.messages.getMessage("notification.category." + category.toLowerCase(), null, am)).isNotBlank();
		}
		assertThat(this.messages.getMessage("notification.role_granted_platform.body", new Object[] { "X" }, Locale.ENGLISH))
			.contains("X");
	}

	@Test
	void theDeclaredTypesBehaveAsDesigned() throws Exception {
		Map<String, Object> role = this.db.queryForMap("select * from notification_types where code = 'ROLE_GRANTED'");
		assertThat(role.get("category")).isEqualTo("ACCOUNT");
		assertThat((String[]) ((java.sql.Array) role.get("default_channels")).getArray()).containsExactly("IN_APP", "PUSH");
		Map<String, Object> test = this.db.queryForMap("select * from notification_types where code = 'TEST_NOTIFICATION'");
		assertThat(test.get("category")).isEqualTo("ACCOUNT");
		assertThat(this.db.queryForObject("select user_can_disable from notification_types where code = 'CONSENT_REQUESTED'",
				Boolean.class)).isFalse();
		assertThat(this.db.queryForObject("select can_be_urgent from notification_types where code = 'ANNOUNCEMENT_PUBLISHED'",
				Boolean.class)).isTrue();
	}

	@Test
	void grantingARoleNotifiesTheGranteeInTheirLanguage() throws Exception {
		Personas.Fixture f = this.personas.fixture();
		var admin = this.personas.platformAdmin();
		var amharic = this.personas.newUser("grantee-am");
		var english = this.personas.newUser("grantee-en");
		this.db.update("update users set preferred_language_code = 'am' where id = ?", amharic.userId());
		this.db.update("update users set preferred_language_code = 'en' where id = ?", english.userId());
		String schoolName = this.db.queryForObject("select name from schools where id = ?", String.class, f.schoolA());

		for (var grantee : List.of(amharic, english)) {
			this.mvc.perform(post("/api/v1/platform/role-assignments").with(admin.bearer())
				.contentType("application/json")
				.content(this.json.writeValueAsString(Map.of("userId", grantee.userId(), "roleCode", "SCHOOL_ADMIN", "scope",
						Map.of("type", "SCHOOL", "schoolId", f.schoolA())))))
				.andExpect(status().isCreated());
		}

		await().atMost(java.time.Duration.ofSeconds(10)).until(() -> this.db.queryForObject(
				"select count(*) from notifications where type_code = 'ROLE_GRANTED' and user_id in (?, ?)", Integer.class,
				amharic.userId(), english.userId()) == 2);
		Map<String, Object> en = this.db.queryForMap(
				"select title, body, school_id, data::text as data, source_type from notifications where user_id = ?", english.userId());
		assertThat(en.get("title")).isEqualTo("You have a new role");
		assertThat(en.get("body")).isEqualTo("You are now School administrator at " + schoolName + ".");
		assertThat(en.get("school_id")).isEqualTo(f.schoolA());
		assertThat(en.get("data").toString()).contains("\"path\": \"/\"");
		assertThat(en.get("source_type")).isEqualTo("ROLE_ASSIGNMENT");
		Map<String, Object> am = this.db.queryForMap("select title, body from notifications where user_id = ?", amharic.userId());
		assertThat(am.get("title")).isEqualTo("አዲስ ሚና ተሰጥቶዎታል");
		assertThat(am.get("body").toString()).contains("የትምህርት ቤት አስተዳዳሪ").contains(schoolName);
	}

	@Test
	void aPlatformRoleReadsWithoutAScope() throws Exception {
		var admin = this.personas.platformAdmin();
		var grantee = this.personas.newUser("grantee-platform");
		this.mvc.perform(post("/api/v1/platform/role-assignments").with(admin.bearer())
			.contentType("application/json")
			.content(this.json.writeValueAsString(Map.of("userId", grantee.userId(), "roleCode", "PLATFORM_MODERATOR", "scope",
					Map.of("type", "PLATFORM")))))
			.andExpect(status().isCreated());
		await().atMost(java.time.Duration.ofSeconds(10)).until(() -> this.db.queryForObject(
				"select count(*) from notifications where user_id = ?", Integer.class, grantee.userId()) == 1);
		assertThat(this.db.queryForObject("select body from notifications where user_id = ?", String.class, grantee.userId()))
			.isEqualTo("You now have the role Platform moderator on the whole platform.");
	}

	@Autowired
	com.nemaz.school.authorization.RoleAssignments roleAssignments;

	@Test
	void systemGrantsAndSelfGrantsStaySilent() throws Exception {
		var system = this.personas.newUser("silent-system");
		this.tx.executeWithoutResult((st) -> this.roleAssignments.grantAsSystem(system.userId(), "SUPPORT_AGENT", AccessScope.platform()));
		var admin = this.personas.withRole("silent-admin", RoleCodes.PLATFORM_ADMIN, AccessScope.platform());
		this.mvc.perform(post("/api/v1/platform/role-assignments").with(admin.bearer())
			.contentType("application/json")
			.content(this.json.writeValueAsString(Map.of("userId", admin.userId(), "roleCode", "PLATFORM_MODERATOR", "scope",
					Map.of("type", "PLATFORM")))))
			.andExpect(status().isCreated());
		assertThat(this.db.queryForObject("select count(*) from role_assignments where user_id = ? and status = 'ACTIVE'",
				Integer.class, admin.userId())).isEqualTo(2);

		await().during(java.time.Duration.ofSeconds(1)).atMost(java.time.Duration.ofSeconds(5)).until(() -> this.db.queryForObject(
				"select count(*) from notifications where user_id in (?, ?)", Integer.class, system.userId(), admin.userId()) == 0);
	}

}
