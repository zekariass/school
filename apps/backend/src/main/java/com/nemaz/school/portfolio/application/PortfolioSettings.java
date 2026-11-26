package com.nemaz.school.portfolio.application;

import java.util.UUID;

import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;
import com.nemaz.school.tenancy.FeatureService;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/**
 * The {@code portfolio.*} settings of a school (R__20_definitions.sql) and the {@code portfolio} feature flag. Every default is
 * the restrictive one except student creation: the audience is capped at GUARDIANS, assistants cannot capture, sharing is off.
 */
@Component
public class PortfolioSettings {

	public static final String FEATURE = "portfolio";

	static final String STUDENT_CREATE = "portfolio.student_create.enabled";

	static final String MAX_VISIBILITY = "portfolio.max_visibility";

	static final String ASSISTANTS_CAPTURE = "portfolio.assistants_can_capture";

	static final String NOTIFY_GUARDIANS = "portfolio.notify_guardians";

	static final String SHARE_TO_STORY = "portfolio.share_to_story.enabled";

	static final String GUARDIAN_COMMENTS = "portfolio.comments.guardians_enabled";

	private final SettingsService settings;

	private final FeatureService features;

	PortfolioSettings(SettingsService settings, FeatureService features) {
		this.settings = settings;
		this.features = features;
	}

	public boolean featureEnabled(UUID schoolId) {
		return this.features.isEnabled(FEATURE, schoolId);
	}

	public boolean studentCreate(UUID schoolId) {
		return this.settings.getBoolean(STUDENT_CREATE, SettingScope.school(schoolId));
	}

	/** The school cap on audiences; an unknown value counts as the most restrictive (STUDENT_TEACHER). */
	public PortfolioVisibility maxVisibility(UUID schoolId) {
		String value = this.settings.getString(MAX_VISIBILITY, SettingScope.school(schoolId)).orElse("GUARDIANS");
		try {
			return PortfolioVisibility.valueOf(value);
		}
		catch (IllegalArgumentException ex) {
			return PortfolioVisibility.STUDENT_TEACHER;
		}
	}

	public boolean assistantsCapture(UUID schoolId) {
		return this.settings.getBoolean(ASSISTANTS_CAPTURE, SettingScope.school(schoolId));
	}

	public boolean notifyGuardians(UUID schoolId) {
		return this.settings.getBoolean(NOTIFY_GUARDIANS, SettingScope.school(schoolId));
	}

	public boolean shareToStory(UUID schoolId) {
		return this.settings.getBoolean(SHARE_TO_STORY, SettingScope.school(schoolId));
	}

	public boolean guardianComments(UUID schoolId) {
		return this.settings.getBoolean(GUARDIAN_COMMENTS, SettingScope.school(schoolId));
	}

	/** The effective audience of an item: the lower of its own visibility and the school cap. */
	public PortfolioVisibility effective(PortfolioVisibility itemVisibility, UUID schoolId) {
		return PortfolioVisibility.least(itemVisibility, maxVisibility(schoolId));
	}

}
