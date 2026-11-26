package com.nemaz.school.communications.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementType;
import com.nemaz.school.system.EmailSender;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/**
 * The announcement settings of a school (R__20_definitions.sql, module {@code announcements}). Defaults are the
 * restrictive ones: students are not addressed, SMS is off and, when on, limited to urgent and safety announcements and to
 * caps, email carries no text. Platform, country and organization administrators can change the defaults above the school.
 */
@Component
public class CommunicationsSettings {

	static final String FEATURE = "announcements";

	static final String TEACHERS = "announcements.teachers_can_announce";

	static final String STUDENTS = "announcements.students_enabled";

	static final String EMAIL = "announcements.channel.email.enabled";

	static final String SMS = "announcements.channel.sms.enabled";

	static final String SMS_TYPES = "announcements.sms.types";

	static final String SMS_PER_ANNOUNCEMENT = "announcements.sms.max_per_announcement";

	static final String SMS_PER_DAY = "announcements.sms.max_per_day";

	static final String EMAIL_BODY = "announcements.email.include_body";

	static final String REMINDER_HOURS = "announcements.reminder.min_hours";

	private final SettingsService settings;

	private final EmailSender email;

	CommunicationsSettings(SettingsService settings, EmailSender email) {
		this.settings = settings;
		this.email = email;
	}

	boolean teachersCanAnnounce(UUID schoolId) {
		return this.settings.getBoolean(TEACHERS, SettingScope.school(schoolId));
	}

	public boolean studentsEnabled(UUID schoolId) {
		return this.settings.getBoolean(STUDENTS, SettingScope.school(schoolId));
	}

	/** Email is available when the school allows it and the platform has outbound email set up. */
	boolean emailAvailable(UUID schoolId) {
		return this.settings.getBoolean(EMAIL, SettingScope.school(schoolId)) && this.email.configured();
	}

	boolean smsEnabled(UUID schoolId) {
		return this.settings.getBoolean(SMS, SettingScope.school(schoolId));
	}

	Set<AnnouncementType> smsTypes(UUID schoolId) {
		List<String> configured = this.settings.getStringList(SMS_TYPES, SettingScope.school(schoolId));
		return configured.stream().map(AnnouncementType::valueOf).collect(Collectors.toSet());
	}

	boolean smsAllowedFor(UUID schoolId, AnnouncementType type) {
		return smsEnabled(schoolId) && smsTypes(schoolId).contains(type);
	}

	int smsPerAnnouncement(UUID schoolId) {
		return this.settings.getInt(SMS_PER_ANNOUNCEMENT, SettingScope.school(schoolId)).orElse(500);
	}

	int smsPerDay(UUID schoolId) {
		return this.settings.getInt(SMS_PER_DAY, SettingScope.school(schoolId)).orElse(1000);
	}

	boolean emailIncludesBody(UUID schoolId) {
		return this.settings.getBoolean(EMAIL_BODY, SettingScope.school(schoolId));
	}

	int reminderMinHours(UUID schoolId) {
		return this.settings.getInt(REMINDER_HOURS, SettingScope.school(schoolId)).orElse(24);
	}

}
