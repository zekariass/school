package com.nemaz.school.messaging.application;

import com.nemaz.school.academic.ClassMembershipChanged;
import com.nemaz.school.guardians.GuardianLinkChanged;
import com.nemaz.school.media.MediaDeleted;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The events messaging reacts to. All handlers are idempotent (delivery is at least once): they recompute the wanted state from
 * the roster and the guardian links and apply the difference ({@link RosterSync}, {@link Safeguarding}). They are tidying up:
 * what people may read is decided live on every request by the eligibility check, so a delayed event never keeps access open.
 */
@Component
class MessagingEvents {

	private final RosterSync roster;

	private final Safeguarding safeguarding;

	private final JdbcClient jdbc;

	MessagingEvents(RosterSync roster, Safeguarding safeguarding, JdbcClient jdbc) {
		this.roster = roster;
		this.safeguarding = safeguarding;
		this.jdbc = jdbc;
	}

	/** A student or teacher joined or left a class: the class conversations follow the roster. */
	@ApplicationModuleListener
	void on(ClassMembershipChanged event) {
		this.roster.reconcileClass(event.classId());
		if (event.memberType() == ClassMembershipChanged.MemberType.STUDENT) {
			this.safeguarding.reconcileStudent(event.memberId());
		}
	}

	/** A guardian link changed: observers of the student's safeguarded conversations and class parent groups follow it. */
	@ApplicationModuleListener
	void on(GuardianLinkChanged event) {
		this.safeguarding.reconcileStudent(event.studentId());
		this.roster.reconcileClassesOfStudent(event.studentId());
	}

	/** A deleted image is no longer attached to its message. */
	@ApplicationModuleListener
	void on(MediaDeleted event) {
		this.jdbc.sql("delete from message_attachments where media_id = :m").param("m", event.mediaId()).update();
	}

}
