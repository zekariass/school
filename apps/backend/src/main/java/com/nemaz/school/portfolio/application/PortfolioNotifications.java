package com.nemaz.school.portfolio.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.media.MediaDeleted;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.portfolio.PortfolioCommentPosted;
import com.nemaz.school.portfolio.PortfolioItemReviewed;
import com.nemaz.school.portfolio.PortfolioItemSubmitted;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The events portfolios reacts to (rule book P3): notifications with <b>generic</b> text (never the title, the reflection, a note,
 * a comment or a child's name; {@code data.path} is {@code /portfolio/items/{id}}), and the tidying after a deleted file. Delivery
 * is at least once, so every handler is idempotent ({@code alreadyNotified}); what is true is re-read when the handler runs: an item
 * that was deleted, hidden or lowered meanwhile notifies nobody.
 */
@Component
class PortfolioNotifications {

	static final String SUBMITTED = "PORTFOLIO_SUBMITTED";

	static final String REVIEWED = "PORTFOLIO_REVIEWED";

	static final String NEW_ITEM = "PORTFOLIO_NEW_ITEM";

	static final String COMMENT = "PORTFOLIO_COMMENT";

	private final NotificationService notifications;

	private final JdbcClient jdbc;

	private final GuardianAccess guardians;

	private final PortfolioAudience audience;

	private final PortfolioSettings settings;

	PortfolioNotifications(NotificationService notifications, JdbcClient jdbc, GuardianAccess guardians, PortfolioAudience audience,
			PortfolioSettings settings) {
		this.notifications = notifications;
		this.jdbc = jdbc;
		this.guardians = guardians;
		this.audience = audience;
		this.settings = settings;
	}

	/** The reviewing teachers of the item's class (LEAD, CO_TEACHER, a matching SUBJECT_TEACHER) hear that an item waits. */
	@ApplicationModuleListener
	void on(PortfolioItemSubmitted event) {
		ItemRow item = this.audience.raw(event.itemId()).orElse(null);
		if (item == null || item.status() != PortfolioStatus.SUBMITTED || item.classId() == null) {
			return;
		}
		List<UUID> teachers = this.jdbc.sql("""
				select distinct ct.teacher_id from class_teachers ct
				where ct.class_id = :class and ct.status = 'ACTIVE'
				  and (ct.role in ('LEAD', 'CO_TEACHER') or (ct.role = 'SUBJECT_TEACHER' and ct.subject_id = cast(:subject as uuid)))""")
			.param("class", item.classId())
			.param("subject", item.subjectId())
			.query(UUID.class)
			.list();
		notifyOnce(teachers, SUBMITTED, "portfolio_approval", event.approvalId(), item);
	}

	/** The student hears about the decision; the guardians hear once about an approved, guardian-visible item. */
	@ApplicationModuleListener
	void on(PortfolioItemReviewed event) {
		ItemRow item = this.audience.raw(event.itemId()).orElse(null);
		if (item == null) {
			return;
		}
		notifyOnce(List.of(item.studentId()), REVIEWED, "portfolio_approval", event.approvalId(), item);
		if (!"APPROVED".equals(event.toStatus()) || item.status() != PortfolioStatus.APPROVED) {
			return;
		}
		UUID schoolId = item.schoolId();
		if (!this.settings.notifyGuardians(schoolId) || !this.settings.featureEnabled(schoolId)
				|| this.settings.effective(item.visibility(), schoolId).rank() < PortfolioVisibility.GUARDIANS.rank()) {
			return;
		}
		boolean studentActive = this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("st", item.studentId())
			.query(Boolean.class)
			.single();
		if (!studentActive) {
			return;
		}
		List<UUID> recipients = new ArrayList<>();
		for (GuardianGrant grant : this.guardians.guardiansOf(item.studentId())) {
			if (grant.allows(GuardianPermission.VIEW_ACADEMICS) && grant.allows(GuardianPermission.RECEIVE_NOTIFICATIONS)) {
				recipients.add(grant.guardianId());
			}
		}
		// one per approved item, ever: the source is the item, not the approval
		notifyOnce(recipients, NEW_ITEM, "portfolio_item", item.id(), item);
	}

	/** The student hears about somebody else's comment (never its text). */
	@ApplicationModuleListener
	void on(PortfolioCommentPosted event) {
		ItemRow item = this.audience.raw(event.itemId()).orElse(null);
		if (item == null || item.status() != PortfolioStatus.APPROVED || item.studentId().equals(event.authorId())) {
			return;
		}
		boolean visible = this.jdbc.sql("select exists (select 1 from portfolio_comments where id = :id and status = 'VISIBLE')")
			.param("id", event.commentId())
			.query(Boolean.class)
			.single();
		if (visible) {
			notifyOnce(List.of(item.studentId()), COMMENT, "portfolio_comment", event.commentId(), item);
		}
	}

	/** A deleted file is no longer attached to its item. */
	@ApplicationModuleListener
	void on(MediaDeleted event) {
		this.jdbc.sql("delete from portfolio_attachments where media_id = :m").param("m", event.mediaId()).update();
	}

	private void notifyOnce(List<UUID> recipients, String type, String sourceType, UUID sourceId, ItemRow item) {
		Set<UUID> fresh = new LinkedHashSet<>();
		for (UUID user : recipients) {
			if (!this.notifications.alreadyNotified(user, type, sourceType, sourceId)) {
				fresh.add(user);
			}
		}
		if (fresh.isEmpty()) {
			return;
		}
		this.notifications.notify(NotificationRequest.to(type, fresh)
			.school(item.schoolId())
			.source(sourceType, sourceId)
			.path("/portfolio/items/" + item.id())
			.build());
	}

}
