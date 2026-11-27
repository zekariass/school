package com.nemaz.school.attendance.application;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.ClassMemberships.ClassTeacher;
import com.nemaz.school.academic.ClassMemberships.StudentClass;
import com.nemaz.school.attendance.AbsenceReported;
import com.nemaz.school.attendance.AttendanceMarked;
import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.common.i18n.SupportedLocales;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.notifications.LocalizedText;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The attendance notifications. Both listeners are idempotent (delivery is at-least-once): a recipient who already has
 * the notification of that source is skipped.
 * <ul>
 * <li>{@code ATTENDANCE_MARKED} to the child's ACTIVE, not custody-restricted guardians with
 * {@code can_receive_notifications}, only for the statuses the school chose
 * ({@code attendance.notify_guardians.statuses}, default ABSENT and LATE). One notification per change of a record
 * (source = record and version), skipped when the record has meanwhile changed again (the newer change speaks for
 * itself). The text names the child by first name, the status and the date: never the note, never other children.</li>
 * <li>{@code ABSENCE_REPORTED} to the LEAD, CO and SUBJECT teachers of the student's classes. The text names the child
 * and the dates, never the reason or the note.</li>
 * </ul>
 */
@Component
class AttendanceNotifications {

	static final String MARKED = "ATTENDANCE_MARKED";

	static final String REPORTED = "ABSENCE_REPORTED";

	private final NotificationService notifications;

	private final GuardianAccess guardians;

	private final ClassMemberships memberships;

	private final AttendanceSettings settings;

	private final JdbcClient jdbc;

	private final MessageSource messages;

	private final SupportedLocales languages;

	AttendanceNotifications(NotificationService notifications, GuardianAccess guardians, ClassMemberships memberships,
			AttendanceSettings settings, JdbcClient jdbc, MessageSource messages, SupportedLocales languages) {
		this.languages = languages;
		this.notifications = notifications;
		this.guardians = guardians;
		this.memberships = memberships;
		this.settings = settings;
		this.jdbc = jdbc;
		this.messages = messages;
	}

	@ApplicationModuleListener
	void on(AttendanceMarked event) {
		if (!this.settings.notifyStatuses(event.schoolId()).contains(event.status())) {
			return;
		}
		Integer currentVersion = this.jdbc.sql("select version from attendance_records where id = :id")
			.param("id", event.recordId())
			.query(Integer.class)
			.optional()
			.orElse(null);
		if (currentVersion == null || currentVersion > event.version()) {
			return;
		}
		UUID source = UUID.nameUUIDFromBytes((event.recordId() + ":" + event.version()).getBytes(StandardCharsets.UTF_8));
		Set<UUID> recipients = new LinkedHashSet<>();
		for (GuardianGrant grant : this.guardians.guardiansOf(event.studentId())) {
			if (grant.allows(GuardianPermission.RECEIVE_NOTIFICATIONS)) {
				recipients.add(grant.guardianId());
			}
		}
		recipients.removeIf((r) -> r.equals(event.markedBy())
				|| this.notifications.alreadyNotified(r, MARKED, "attendance_record", source));
		if (recipients.isEmpty()) {
			return;
		}
		this.notifications.notify(NotificationRequest.to(MARKED, recipients)
			.args(firstName(event.studentId()), statusText(event.status()), event.attendanceDate().toString())
			.school(event.schoolId())
			.source("attendance_record", source)
			.path("/children")
			.data("studentId", event.studentId().toString())
			.data("date", event.attendanceDate().toString())
			.build());
	}

	@ApplicationModuleListener
	void on(AbsenceReported event) {
		List<UUID> classIds = this.memberships.activeClassesOf(List.of(event.studentId()))
			.getOrDefault(event.studentId(), List.of())
			.stream()
			.map(StudentClass::classId)
			.toList();
		Map<UUID, UUID> teacherToClass = new LinkedHashMap<>();
		for (ClassTeacher teacher : this.memberships.activeTeachers(classIds)) {
			if (teacher.role() != TeacherRole.ASSISTANT) {
				teacherToClass.putIfAbsent(teacher.teacherId(), teacher.classId());
			}
		}
		String child = firstName(event.studentId());
		for (Map.Entry<UUID, UUID> entry : teacherToClass.entrySet()) {
			UUID teacher = entry.getKey();
			if (this.notifications.alreadyNotified(teacher, REPORTED, "absence_report", event.reportId())) {
				continue;
			}
			this.notifications.notify(NotificationRequest.to(REPORTED, teacher)
				.args(child, event.fromDate().toString(), event.toDate().toString())
				.school(event.schoolId())
				.source("absence_report", event.reportId())
				.path("/classes/" + entry.getValue() + "/attendance")
				.data("classId", entry.getValue().toString())
				.data("studentId", event.studentId().toString())
				.build());
		}
	}

	private String firstName(UUID userId) {
		return this.jdbc.sql("select given_name from users where id = :id")
			.param("id", userId)
			.query(String.class)
			.optional()
			.orElse("");
	}

	/** The status word in every language the bundles have; each recipient gets theirs. */
	private LocalizedText statusText(AttendanceStatus status) {
		String key = "attendance.status." + status.name().toLowerCase(Locale.ROOT);
		Map<String, String> values = new LinkedHashMap<>();
		for (String language : this.languages.codes()) {
			values.put(language, this.messages.getMessage(key, null, key, Locale.forLanguageTag(language)));
		}
		return LocalizedText.of(values);
	}

}
