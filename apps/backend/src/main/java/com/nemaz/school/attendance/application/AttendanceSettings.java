package com.nemaz.school.attendance.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.stereotype.Component;

/** The attendance settings of a school (R__20_definitions.sql). */
@Component
class AttendanceSettings {

	static final String SLOTS = "attendance.slots";

	static final String ASSISTANTS_CAN_TAKE = "attendance.assistants_can_take";

	static final String LOCK_AFTER_HOURS = "attendance.lock_after_hours";

	static final String NOTIFY_STATUSES = "attendance.notify_guardians.statuses";

	static final String STAFF_ENABLED = "attendance.staff.enabled";

	static final String METHODS_ENABLED = "attendance.methods.enabled";

	static final String QR_ROTATION_SECONDS = "attendance.qr.rotation_seconds";

	static final String STAFF_LATE_AFTER_MINUTES = "attendance.staff.late_after_minutes";

	static final String METHOD_QR_ROTATING = "QR_ROTATING";

	static final String METHOD_QR_STATIC = "QR_STATIC";

	static final int DEFAULT_LOCK_AFTER_HOURS = 24;

	static final int DEFAULT_ROTATION_SECONDS = 30;

	private final SettingsService settings;

	AttendanceSettings(SettingsService settings) {
		this.settings = settings;
	}

	/** The roll calls the school's classes take per day, in configuration order. */
	List<String> slots(UUID schoolId) {
		List<String> slots = this.settings.getStringList(SLOTS, SettingScope.school(schoolId));
		return slots.isEmpty() ? List.of("DAY") : slots;
	}

	boolean assistantsCanTake(UUID schoolId) {
		return this.settings.getBoolean(ASSISTANTS_CAN_TAKE, SettingScope.school(schoolId));
	}

	int lockAfterHours(UUID schoolId) {
		return this.settings.getInt(LOCK_AFTER_HOURS, SettingScope.school(schoolId)).orElse(DEFAULT_LOCK_AFTER_HOURS);
	}

	/** Staff attendance (M7d) is off until a school switches it on. */
	boolean staffEnabled(UUID schoolId) {
		return this.settings.getBoolean(STAFF_ENABLED, SettingScope.school(schoolId));
	}

	/** The methods the school turned on besides manual marking (which is always available): QR_ROTATING and QR_STATIC. */
	Set<String> methods(UUID schoolId) {
		return Set.copyOf(this.settings.getStringList(METHODS_ENABLED, SettingScope.school(schoolId)));
	}

	boolean methodEnabled(UUID schoolId, String method) {
		return methods(schoolId).contains(method);
	}

	/** Seconds a rotating code stays the same (new sessions snapshot it). */
	int rotationSeconds(UUID schoolId) {
		return this.settings.getInt(QR_ROTATION_SECONDS, SettingScope.school(schoolId)).orElse(DEFAULT_ROTATION_SECONDS);
	}

	/** Minutes after a staff session opened from which a check-in is LATE; 0: nobody is late. */
	int staffLateAfterMinutes(UUID schoolId) {
		return this.settings.getInt(STAFF_LATE_AFTER_MINUTES, SettingScope.school(schoolId)).orElse(0);
	}

	/** The marks that notify guardians. */
	Set<AttendanceStatus> notifyStatuses(UUID schoolId) {
		Set<AttendanceStatus> statuses = EnumSet.noneOf(AttendanceStatus.class);
		for (String status : this.settings.getStringList(NOTIFY_STATUSES, SettingScope.school(schoolId))) {
			statuses.add(AttendanceStatus.valueOf(status));
		}
		return statuses;
	}

}
