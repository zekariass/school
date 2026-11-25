package com.nemaz.school.engagement.application;

import java.util.UUID;

import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/**
 * The engagement settings of a school (R__20_definitions.sql). Leaderboards and public rankings are off by default: a
 * child never sees other children's points unless the school turned it on.
 */
@Component
class EngagementSettings {

	static final String NEEDS_WORK = "engagement.needs_work.enabled";

	static final String BADGES = "engagement.badges.enabled";

	static final String LEADERBOARD = "engagement.leaderboard.enabled";

	static final String PUBLIC_RANKINGS = "engagement.public_rankings.enabled";

	static final String UNDO_WINDOW_SECONDS = "engagement.points.undo_window_seconds";

	static final int DEFAULT_UNDO_WINDOW_SECONDS = 120;

	private final SettingsService settings;

	EngagementSettings(SettingsService settings) {
		this.settings = settings;
	}

	boolean needsWork(UUID schoolId) {
		return this.settings.getBoolean(NEEDS_WORK, SettingScope.school(schoolId));
	}

	boolean badges(UUID schoolId) {
		return this.settings.getBoolean(BADGES, SettingScope.school(schoolId));
	}

	boolean leaderboard(UUID schoolId) {
		return this.settings.getBoolean(LEADERBOARD, SettingScope.school(schoolId));
	}

	boolean publicRankings(UUID schoolId) {
		return this.settings.getBoolean(PUBLIC_RANKINGS, SettingScope.school(schoolId));
	}

	int undoWindowSeconds(UUID schoolId) {
		return this.settings.getInt(UNDO_WINDOW_SECONDS, SettingScope.school(schoolId)).orElse(DEFAULT_UNDO_WINDOW_SECONDS);
	}

}
