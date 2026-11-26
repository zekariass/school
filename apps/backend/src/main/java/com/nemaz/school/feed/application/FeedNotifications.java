package com.nemaz.school.feed.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.ClassMemberships.ClassTeacher;
import com.nemaz.school.feed.ContributionDecided;
import com.nemaz.school.feed.ContributionSubmitted;
import com.nemaz.school.feed.PostPublishBlocked;
import com.nemaz.school.feed.PostPublished;
import com.nemaz.school.feed.StoryPublished;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The feed's notifications. Every listener is idempotent (delivery is at-least-once): a recipient who already has the
 * notification of that source is skipped, and nothing is sent for content that is no longer published.
 * <ul>
 * <li>{@code NEW_POST} and {@code NEW_STORY}: the ACTIVE, not custody-restricted guardians with
 * {@code can_receive_notifications} of the <b>tagged</b> students; with {@code feed.notify_class_members} also the
 * students of the class and their guardians (school-wide content: the guardians of every ACTIVE student). The text
 * carries the class (or school) name and the author's name for staff authors, never the post text and never a child's
 * name; a family contribution is announced without naming its author. {@code data.path} is {@code /feed/posts/{id}} or
 * {@code /feed/stories/{id}} (placeholders for the M8 clients).</li>
 * <li>{@code CONTRIBUTION_PENDING}: the class's LEAD teachers. {@code POST_APPROVED} / {@code POST_REJECTED}: the
 * contributor (the reason stays in the app).</li>
 * <li>{@code POST_PUBLISH_BLOCKED}: the author of a scheduled post the consent gate stopped.</li>
 * </ul>
 */
@Component
class FeedNotifications {

	static final String NEW_POST = "NEW_POST";

	static final String NEW_STORY = "NEW_STORY";

	static final String PENDING = "CONTRIBUTION_PENDING";

	static final String APPROVED = "POST_APPROVED";

	static final String REJECTED = "POST_REJECTED";

	static final String BLOCKED = "POST_PUBLISH_BLOCKED";

	private final NotificationService notifications;

	private final GuardianAccess guardians;

	private final ClassMemberships memberships;

	private final FeedSettings settings;

	private final JdbcClient jdbc;

	FeedNotifications(NotificationService notifications, GuardianAccess guardians, ClassMemberships memberships,
			FeedSettings settings, JdbcClient jdbc) {
		this.notifications = notifications;
		this.guardians = guardians;
		this.memberships = memberships;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(PostPublished event) {
		boolean live = this.jdbc.sql("select exists (select 1 from posts where id = :id and status = 'PUBLISHED' and deleted_at is null)")
			.param("id", event.postId())
			.query(Boolean.class)
			.single();
		if (!live) {
			return;
		}
		Set<UUID> recipients = audience("post_student_tags", "post_id", event.postId(), event.schoolId(), event.classId());
		recipients.remove(event.authorId());
		recipients.removeIf((r) -> this.notifications.alreadyNotified(r, NEW_POST, "post", event.postId()));
		if (recipients.isEmpty()) {
			return;
		}
		String where = placeName(event.schoolId(), event.classId());
		NotificationRequest.Builder request = NotificationRequest.to(NEW_POST, recipients)
			.school(event.schoolId())
			.source("post", event.postId())
			.path("/feed/posts/" + event.postId())
			.data("postId", event.postId().toString());
		if (event.contribution()) {
			request.messageKey("notification.new_post_contribution").args(where);
		}
		else {
			request.args(where, displayName(event.authorId()));
		}
		this.notifications.notify(request.build());
	}

	@ApplicationModuleListener
	void on(StoryPublished event) {
		boolean live = this.jdbc.sql("select exists (select 1 from stories where id = :id and status = 'PUBLISHED' and deleted_at is null)")
			.param("id", event.storyId())
			.query(Boolean.class)
			.single();
		if (!live) {
			return;
		}
		Set<UUID> recipients = audience("story_student_tags", "story_id", event.storyId(), event.schoolId(), event.classId());
		recipients.remove(event.authorId());
		recipients.removeIf((r) -> this.notifications.alreadyNotified(r, NEW_STORY, "story", event.storyId()));
		if (recipients.isEmpty()) {
			return;
		}
		this.notifications.notify(NotificationRequest.to(NEW_STORY, recipients)
			.args(placeName(event.schoolId(), event.classId()), displayName(event.authorId()))
			.school(event.schoolId())
			.source("story", event.storyId())
			.path("/feed/stories/" + event.storyId())
			.data("storyId", event.storyId().toString())
			.build());
	}

	@ApplicationModuleListener
	void on(ContributionSubmitted event) {
		Set<UUID> leads = new LinkedHashSet<>();
		for (ClassTeacher teacher : this.memberships.activeTeachers(List.of(event.classId()))) {
			if (teacher.role() == TeacherRole.LEAD) {
				leads.add(teacher.teacherId());
			}
		}
		leads.remove(event.contributorId());
		leads.removeIf((r) -> this.notifications.alreadyNotified(r, PENDING, "post", event.postId()));
		if (leads.isEmpty()) {
			return;
		}
		this.notifications.notify(NotificationRequest.to(PENDING, leads)
			.args(placeName(event.schoolId(), event.classId()))
			.school(event.schoolId())
			.source("post", event.postId())
			.path("/feed/approvals")
			.data("classId", event.classId().toString())
			.build());
	}

	@ApplicationModuleListener
	void on(ContributionDecided event) {
		String type = event.approved() ? APPROVED : REJECTED;
		if (this.notifications.alreadyNotified(event.contributorId(), type, "post", event.postId())) {
			return;
		}
		this.notifications.notify(NotificationRequest.to(type, event.contributorId())
			.args(placeName(event.schoolId(), event.classId()))
			.school(event.schoolId())
			.source("post", event.postId())
			.path("/feed/posts/" + event.postId())
			.data("postId", event.postId().toString())
			.build());
	}

	@ApplicationModuleListener
	void on(PostPublishBlocked event) {
		if (this.notifications.alreadyNotified(event.authorId(), BLOCKED, "post", event.postId())) {
			return;
		}
		this.notifications.notify(NotificationRequest.to(BLOCKED, event.authorId())
			.args(placeName(event.schoolId(), event.classId()))
			.school(event.schoolId())
			.source("post", event.postId())
			.path("/feed/posts/" + event.postId())
			.data("postId", event.postId().toString())
			.build());
	}

	/** The guardians of tagged students, plus (setting) the class's or school's families and students. */
	private Set<UUID> audience(String tagTable, String tagColumn, UUID contentId, UUID schoolId, UUID classId) {
		Set<UUID> recipients = new LinkedHashSet<>();
		List<UUID> tagged = this.jdbc.sql("select student_id from " + tagTable + " where " + tagColumn + " = :id")
			.param("id", contentId)
			.query(UUID.class)
			.list();
		for (UUID student : tagged) {
			for (GuardianGrant grant : this.guardians.guardiansOf(student)) {
				if (grant.allows(GuardianPermission.RECEIVE_NOTIFICATIONS) && grant.allows(GuardianPermission.VIEW_ACADEMICS)) {
					recipients.add(grant.guardianId());
				}
			}
		}
		if (this.settings.notifyClassMembers(schoolId)) {
			String members = (classId != null)
					? "select cs.student_id from class_students cs where cs.class_id = :scope and cs.status = 'ACTIVE'"
					: "select ss.student_id from school_students ss where ss.school_id = :scope and ss.status = 'ACTIVE'";
			UUID scope = (classId != null) ? classId : schoolId;
			recipients.addAll(this.jdbc.sql(members).param("scope", scope).query(UUID.class).list());
			recipients.addAll(this.jdbc.sql("""
					select distinct sg.guardian_id from student_guardians sg
					where sg.student_id in (""" + members + """
					) and sg.status = 'ACTIVE' and not sg.custody_restricted and sg.can_view_academics
					  and sg.can_receive_notifications""")
				.param("scope", scope)
				.query(UUID.class)
				.list());
		}
		return recipients;
	}

	private String placeName(UUID schoolId, UUID classId) {
		if (classId != null) {
			return this.jdbc.sql("select name from classes where id = :id").param("id", classId).query(String.class).optional().orElse("");
		}
		return this.jdbc.sql("select name from schools where id = :id").param("id", schoolId).query(String.class).optional().orElse("");
	}

	private String displayName(UUID userId) {
		return this.jdbc.sql("select display_name from users where id = :id").param("id", userId).query(String.class).optional().orElse("");
	}

}
