package com.nemaz.school.attendance.application;

import com.nemaz.school.people.StaffDeactivated;
import com.nemaz.school.people.StudentWithdrawnFromSchool;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A card dies with the person's place in the school: when a student is withdrawn or a staff member leaves, their live
 * credential is revoked (reason LEFT_SCHOOL), so a lost or kept card of someone who left is refused and logged at its next
 * scan. Both listeners are idempotent (delivery is at-least-once): revoking what is already revoked changes nothing.
 */
@Component
class CredentialLifecycle {

	private final CredentialService credentials;

	CredentialLifecycle(CredentialService credentials) {
		this.credentials = credentials;
	}

	@ApplicationModuleListener
	void on(StudentWithdrawnFromSchool event) {
		this.credentials.revokeForLeaver(event.schoolId(), true, event.studentId(), event.actorId());
	}

	@ApplicationModuleListener
	void on(StaffDeactivated event) {
		this.credentials.revokeForLeaver(event.schoolId(), false, event.staffId(), event.actorId());
	}

}
