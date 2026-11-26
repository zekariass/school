package com.nemaz.school.portfolio;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.authorization.RoleCodes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P8: the settings, the permission and the feature flag are seeded as documented. */
@IntegrationTest
class PortfolioSettingsTest extends PortfolioTestSupport {

	@Test
	void settingsFlagAndPermissionAreSeeded() {
		record Setting(String key, String defaultValue) {
		}
		List<Setting> expected = List.of(new Setting("portfolio.student_create.enabled", "true"), new Setting("portfolio.max_visibility", "\"GUARDIANS\""),
				new Setting("portfolio.assistants_can_capture", "false"), new Setting("portfolio.notify_guardians", "true"),
				new Setting("portfolio.share_to_story.enabled", "false"), new Setting("portfolio.comments.guardians_enabled", "true"));
		for (Setting s : expected) {
			assertThat(this.jdbc.queryForObject("select default_value::text from setting_definitions where key = ?", String.class, s.key()))
				.as(s.key()).isEqualTo(s.defaultValue());
			assertThat(this.jdbc.queryForObject("select 'SCHOOL' = any (allowed_scopes) from setting_definitions where key = ?", Boolean.class, s.key())).isTrue();
		}
		// the school cap accepts the four audiences and nothing else
		UUID school = basicSchool("settings");
		for (String ok : List.of("STUDENT_TEACHER", "GUARDIANS", "CLASS", "SCHOOL")) {
			setting(school, "portfolio.max_visibility", ok);
		}
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> setting(school, "portfolio.max_visibility", "EVERYONE")).isInstanceOf(RuntimeException.class);
		assertThat(this.jdbc.queryForObject("select default_enabled from feature_definitions where key = 'portfolio'", Boolean.class)).isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from permissions where code = 'school.portfolios.review'", Integer.class)).isEqualTo(1);
		// the administrators who oversee a school hold the permission; nobody else does
		List<String> roles = this.jdbc.queryForList("select role_code from role_permissions where permission_code = 'school.portfolios.review' order by 1", String.class);
		assertThat(roles).containsExactlyInAnyOrder(RoleCodes.PLATFORM_ADMIN, RoleCodes.COUNTRY_ADMIN, RoleCodes.ORGANIZATION_ADMIN, RoleCodes.SCHOOL_ADMIN);
		// portfolios belong to SCHOOL_BASIC and up
		assertThat(this.jdbc.queryForList("select p.code from plan_entitlements e join subscription_plans p on p.id = e.plan_id where e.feature_key = 'portfolio' and e.enabled order by 1",
				String.class)).doesNotContain("SCHOOL_FREE").contains("SCHOOL_BASIC");
		// the notification types of portfolios exist
		assertThat(this.jdbc.queryForList("select code from notification_types where code like 'PORTFOLIO_%' order by 1", String.class))
			.containsExactly("PORTFOLIO_COMMENT", "PORTFOLIO_NEW_ITEM", "PORTFOLIO_REVIEWED", "PORTFOLIO_SUBMITTED");
	}

}
