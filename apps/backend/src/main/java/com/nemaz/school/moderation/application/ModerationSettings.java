package com.nemaz.school.moderation.application;

import java.util.UUID;

import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/** The safeguarding settings (platform and country level), resolved for a school (null: the platform). */
@Component
class ModerationSettings {

	private final SettingsService settings;

	ModerationSettings(SettingsService settings) {
		this.settings = settings;
	}

	private static SettingScope scope(UUID schoolId) {
		return (schoolId != null) ? SettingScope.school(schoolId) : SettingScope.platform();
	}

	int dailyLimit(UUID schoolId, boolean student) {
		String key = student ? "safeguarding.report.student_daily_limit" : "safeguarding.report.daily_limit";
		return this.settings.getInt(key, scope(schoolId)).orElse(student ? 10 : 20);
	}

	int priorityThreshold(UUID schoolId) {
		return this.settings.getInt("safeguarding.case.priority_threshold", scope(schoolId)).orElse(3);
	}

	int caseRetentionYears(UUID schoolId) {
		return this.settings.getInt("safeguarding.retention.case_years", scope(schoolId)).orElse(2);
	}

}
