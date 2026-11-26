package com.nemaz.school.calendar.application;

import java.util.UUID;

import com.nemaz.school.tenancy.FeatureService;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/**
 * The calendar settings of a school (R__20_definitions.sql, module {@code calendar}) and the {@code calendar} feature
 * flag. Defaults are the quiet ones: ordinary events do not push when created; cancellations, moves and parent meetings do.
 */
@Component
public class CalendarSettings {

	static final String FEATURE = "calendar";

	static final String TEACHERS = "calendar.teachers_can_create_class_events";

	static final String NOTIFY_NEW = "calendar.notify.new_events";

	static final String NOTIFY_CHANGES = "calendar.notify.changes";

	static final String REMINDER_EVENT = "calendar.reminder.event_minutes";

	static final String REMINDER_MEETING = "calendar.reminder.parent_meeting_minutes";

	static final String REMINDER_AUDIENCE = "calendar.reminder.audience";

	/** The longest lead any setting allows (7 days): the reminder job looks this far ahead. */
	static final int MAX_LEAD_MINUTES = 10080;

	private final SettingsService settings;

	private final FeatureService features;

	CalendarSettings(SettingsService settings, FeatureService features) {
		this.settings = settings;
		this.features = features;
	}

	public boolean enabled(UUID schoolId) {
		return this.features.isEnabled(FEATURE, schoolId);
	}

	boolean teachersCanCreateClassEvents(UUID schoolId) {
		return this.settings.getBoolean(TEACHERS, SettingScope.school(schoolId));
	}

	boolean notifyNewEvents(UUID schoolId) {
		return this.settings.getBoolean(NOTIFY_NEW, SettingScope.school(schoolId));
	}

	boolean notifyChanges(UUID schoolId) {
		return this.settings.getBoolean(NOTIFY_CHANGES, SettingScope.school(schoolId));
	}

	/** Minutes before the start; 0 = no reminder. A null school means a personal event (platform default). */
	int eventReminderMinutes(UUID schoolId) {
		return minutes(REMINDER_EVENT, schoolId, 1440);
	}

	int meetingReminderMinutes(UUID schoolId) {
		return minutes(REMINDER_MEETING, schoolId, 60);
	}

	boolean remindEveryone(UUID schoolId) {
		return "EVERYONE".equals(this.settings.getString(REMINDER_AUDIENCE, SettingScope.school(schoolId)).orElse("PARTICIPANTS"));
	}

	private int minutes(String key, UUID schoolId, int fallback) {
		SettingScope scope = (schoolId != null) ? SettingScope.school(schoolId) : SettingScope.platform();
		return Math.max(0, Math.min(MAX_LEAD_MINUTES, this.settings.getInt(key, scope).orElse(fallback)));
	}

}
