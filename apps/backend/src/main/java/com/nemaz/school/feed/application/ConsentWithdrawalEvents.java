package com.nemaz.school.feed.application;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.consent.ConsentChanged;
import com.nemaz.school.consent.ConsentService;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A guardian refused or withdrew media-publication consent for a child (R-09). The hiding itself does not wait for this
 * listener: every read of a post or story re-evaluates the consent of its tagged students ({@link ConsentWithholding}), so
 * the photos are withheld from the moment the decision is committed, and they come back by themselves if consent is
 * granted again. What this listener adds is the human side: it tells the author of every published post and story that
 * tags the child and carries photos that the photos are now hidden, and audits it.
 * <p>
 * Idempotent (delivery is at-least-once): the notification is keyed to (content, consent record), so a redelivery sends
 * nothing and a later withdrawal sends again. The current status is re-read first: a decision that was undone in the
 * meantime notifies nobody.
 */
@Component
class ConsentWithdrawalEvents {

	static final String TYPE = "POST_MEDIA_WITHHELD";

	private final JdbcClient jdbc;

	private final ConsentService consent;

	private final NotificationService notifications;

	private final AuditLog audit;

	private final Clock clock;

	ConsentWithdrawalEvents(JdbcClient jdbc, ConsentService consent, NotificationService notifications, AuditLog audit,
			Clock clock) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.consent = consent;
		this.notifications = notifications;
		this.audit = audit;
	}

	@ApplicationModuleListener
	void on(ConsentChanged event) {
		boolean refused = "DENIED".equals(event.decision()) || "WITHDRAWN".equals(event.decision());
		if (event.consentType() != ConsentType.MEDIA_PUBLICATION || !refused || event.schoolId() == null) {
			return;
		}
		UUID student = event.subjectUserId();
		if (this.consent.mediaPublicationAllowed(student, event.schoolId())) {
			return;
		}
		record Content(UUID id, UUID authorId, UUID classId, String kind) {
		}
		List<Content> contents = this.jdbc.sql("""
				select p.id, p.author_id, p.class_id, 'post' as kind from post_student_tags t join posts p on p.id = t.post_id
				where t.student_id = :s and p.school_id = :school and p.status = 'PUBLISHED' and p.deleted_at is null
				  and exists (select 1 from post_media pm where pm.post_id = p.id)
				union all
				select s.id, s.author_id, s.class_id, 'story' from story_student_tags t join stories s on s.id = t.story_id
				where t.student_id = :s and s.school_id = :school and s.status = 'PUBLISHED' and s.deleted_at is null
				  and s.expires_at > :now and s.media_id is not null""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("s", student)
			.param("school", event.schoolId())
			.query((rs, i) -> new Content(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class),
					rs.getObject("class_id", UUID.class), rs.getString("kind")))
			.list();
		for (Content content : contents) {
			UUID source = UUID.nameUUIDFromBytes((content.kind() + ":" + content.id() + ":" + event.recordId())
				.getBytes(StandardCharsets.UTF_8));
			if (this.notifications.alreadyNotified(content.authorId(), TYPE, "feed_content", source)) {
				continue;
			}
			String where = (content.classId() != null)
					? this.jdbc.sql("select name from classes where id = :id").param("id", content.classId()).query(String.class).single()
					: this.jdbc.sql("select name from schools where id = :id").param("id", event.schoolId()).query(String.class).single();
			this.notifications.notify(NotificationRequest.to(TYPE, content.authorId())
				.args(where)
				.school(event.schoolId())
				.source("feed_content", source)
				.path("/feed/" + content.kind() + "s/" + content.id())
				.data(content.kind() + "Id", content.id().toString())
				.build());
			this.audit.record(AuditEntry.of("feed." + content.kind() + ".media_withheld", AuditOutcome.SUCCESS)
				.actor(null)
				.target(content.kind(), content.id())
				.school(event.schoolId())
				.metadata("consentRecordId", event.recordId().toString()));
		}
	}

}
