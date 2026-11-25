package com.nemaz.school.consent.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianLinkChanged;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Asks guardians for consent ({@code CONSENT_REQUESTED}, a locked type: preferences cannot switch it off) when a link
 * becomes ACTIVE (one request per link, for the child's school) and when a school publishes a policy version that
 * requires new consent (one request per guardian and version). Only ACTIVE, not custody-restricted links with
 * {@code can_give_consent} are asked. Idempotent: delivery of the triggering events is at-least-once.
 */
@Component
class ConsentRequests {

	private static final String TYPE = "CONSENT_REQUESTED";

	private final GuardianAccess guardians;

	private final NotificationService notifications;

	private final TenantDirectory directory;

	private final JdbcClient jdbc;

	ConsentRequests(GuardianAccess guardians, NotificationService notifications, TenantDirectory directory,
			JdbcClient jdbc) {
		this.guardians = guardians;
		this.notifications = notifications;
		this.directory = directory;
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(GuardianLinkChanged event) {
		if (event.change() != GuardianLinkChanged.Change.ACTIVATED) {
			return;
		}
		GuardianGrant link = this.guardians.activeLink(event.linkId())
			.filter((g) -> g.allows(GuardianPermission.GIVE_CONSENT))
			.orElse(null);
		if (link == null || this.notifications.alreadyNotified(link.guardianId(), TYPE, "guardian_link", link.linkId())) {
			return;
		}
		UUID schoolId = (event.schoolId() != null && activeIn(link.studentId(), event.schoolId())) ? event.schoolId()
				: firstSchool(link.studentId());
		if (schoolId == null) {
			return;
		}
		ask(link.guardianId(), link.studentId(), schoolId, "guardian_link", link.linkId());
	}

	@ApplicationModuleListener
	void on(PolicyVersionPublished event) {
		if (!event.requiresReconsent() || event.schoolId() == null || !event.policyType().forChildren()) {
			return;
		}
		List<UUID> students = this.jdbc.sql("select student_id from school_students where school_id = :s and status = 'ACTIVE'")
			.param("s", event.schoolId())
			.query(UUID.class)
			.list();
		for (UUID studentId : students) {
			for (GuardianGrant link : this.guardians.guardiansOf(studentId)) {
				if (link.allows(GuardianPermission.GIVE_CONSENT) && !this.notifications.alreadyNotified(link.guardianId(),
						TYPE, "policy_version", event.versionId())) {
					ask(link.guardianId(), studentId, event.schoolId(), "policy_version", event.versionId());
				}
			}
		}
	}

	private void ask(UUID guardianId, UUID studentId, UUID schoolId, String sourceType, UUID sourceId) {
		String school = this.directory.school(schoolId).map(SchoolRef::name).orElse("");
		String child = this.jdbc.sql("select given_name from users where id = :id")
			.param("id", studentId)
			.query(String.class)
			.optional()
			.orElse("");
		this.notifications.notify(NotificationRequest.to(TYPE, guardianId)
			.args(school, child)
			.school(schoolId)
			.source(sourceType, sourceId)
			.path("/consents")
			.data("studentId", studentId.toString())
			.build());
	}

	private boolean activeIn(UUID studentId, UUID schoolId) {
		return this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	private UUID firstSchool(UUID studentId) {
		return this.jdbc.sql("""
				select school_id from school_students where student_id = :st and status = 'ACTIVE' order by created_at limit 1""")
			.param("st", studentId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

}
