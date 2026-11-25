package com.nemaz.school.guardians.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Notifications of the link lifecycle (in the caller's transaction; texts in each recipient's language):
 * {@code GUARDIAN_LINK_REQUESTED} to the verifiers of a new PENDING link (holders of {@code school.guardians.manage}
 * assigned at the school itself, and the LEAD teachers of the child's classes while teachers verify);
 * {@code GUARDIAN_LINK_APPROVED} / {@code GUARDIAN_LINK_REJECTED} to the guardian. Never sent for custody-restricted
 * links. Arguments are names only, never contact details or notes.
 */
@Component
class GuardianNotifications {

	private final NotificationService notifications;

	private final JdbcClient jdbc;

	private final SettingsService settings;

	private final TenantDirectory directory;

	GuardianNotifications(NotificationService notifications, JdbcClient jdbc, SettingsService settings,
			TenantDirectory directory) {
		this.notifications = notifications;
		this.jdbc = jdbc;
		this.settings = settings;
		this.directory = directory;
	}

	void requested(UUID linkId, UUID schoolId, UUID studentId, UUID guardianId) {
		Set<UUID> recipients = new LinkedHashSet<>(this.jdbc.sql("""
				select distinct ra.user_id from role_assignments ra
				join role_permissions rp on rp.role_code = ra.role_code and rp.permission_code = :p
				where ra.status = 'ACTIVE' and ra.scope_type = 'SCHOOL' and ra.school_id = :s""")
			.param("p", Permissions.SCHOOL_GUARDIANS_MANAGE)
			.param("s", schoolId)
			.query(UUID.class)
			.list());
		if (this.settings.getBoolean(GuardianRules.TEACHERS_CAN_VERIFY, SettingScope.school(schoolId))) {
			recipients.addAll(this.jdbc.sql("""
					select distinct ct.teacher_id from class_teachers ct
					join class_students cs on cs.class_id = ct.class_id and cs.status = 'ACTIVE'
					where ct.school_id = :s and ct.status = 'ACTIVE' and ct.role = 'LEAD' and cs.student_id = :st""")
				.param("s", schoolId)
				.param("st", studentId)
				.query(UUID.class)
				.list());
		}
		recipients.remove(guardianId);
		if (recipients.isEmpty()) {
			return;
		}
		String slug = this.directory.school(schoolId).map(TenantDirectory.SchoolRef::slug).orElse(null);
		NotificationRequest.Builder request = NotificationRequest.to("GUARDIAN_LINK_REQUESTED", recipients)
			.args(name(guardianId), givenName(studentId))
			.school(schoolId)
			.source("guardian_link", linkId)
			.data("linkId", linkId.toString());
		if (slug != null) {
			request.path("/s/" + slug + "/guardian-links");
		}
		this.notifications.notify(request.build());
	}

	void approved(UUID linkId, UUID schoolId, UUID studentId, UUID guardianId) {
		this.notifications.notify(NotificationRequest.to("GUARDIAN_LINK_APPROVED", guardianId)
			.args(name(studentId))
			.school(schoolId)
			.source("guardian_link", linkId)
			.path("/children")
			.data("studentId", studentId.toString())
			.build());
	}

	void rejected(UUID linkId, UUID schoolId, UUID studentId, UUID guardianId) {
		String school = (schoolId != null) ? this.directory.school(schoolId).map(TenantDirectory.SchoolRef::name).orElse("")
				: "";
		this.notifications.notify(NotificationRequest.to("GUARDIAN_LINK_REJECTED", guardianId)
			.args(givenName(studentId), school)
			.school(schoolId)
			.source("guardian_link", linkId)
			.path("/children")
			.build());
	}

	private String name(UUID userId) {
		return this.jdbc.sql("select display_name from users where id = :id")
			.param("id", userId)
			.query(String.class)
			.optional()
			.orElse("");
	}

	private String givenName(UUID userId) {
		return this.jdbc.sql("select given_name from users where id = :id")
			.param("id", userId)
			.query(String.class)
			.optional()
			.orElse("");
	}

}
