package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.ContributionDecided;
import com.nemaz.school.feed.ContributionSubmitted;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedMediaRef;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedModels.PostStatus;
import com.nemaz.school.feed.application.FeedModels.PostView;
import com.nemaz.school.feed.application.FeedModels.PublishMode;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.feed.application.PostService.PostCommand;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parent and student contributions to a class feed (spec §18). The school's settings decide whether they are accepted
 * at all and whether a teacher approves them first; the rules below are fixed.
 * <ul>
 * <li><b>Who:</b> a guardian whose ACTIVE, not custody-restricted link to a child ACTIVE in the class has both
 * {@code can_view_academics} and {@code can_message_staff} (when {@code feed.parent_contributions.enabled}); a student
 * ACTIVE in the class (when {@code feed.student_contributions.enabled}). Scope is the class only.</li>
 * <li><b>What:</b> text and photos (the kinds in {@code feed.parent_contributions.allowed_media}, restricted to what the
 * feed supports); a guardian may tag only their own children; a student tags nobody. A contributor can never confirm that
 * nobody without consent appears: that statement is the approver's.</li>
 * <li><b>Approval:</b> student contributions always wait for a teacher; guardian contributions wait unless the school
 * switched {@code feed.parent_contributions.approval_required} off, and then a photo must show the contributor's own
 * (tagged, consented) child. Approvers are the class's LEAD teacher, class managers and school feed managers. Approving
 * runs the consent gate; rejecting needs a reason and deletes the photos.</li>
 * <li>The author shows as a role label (and name only to staff); a contribution can be edited only while pending.</li>
 * </ul>
 */
@Service
public class ContributionService {

	static final int CONTRIBUTIONS_PER_DAY = 20;

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final PostService posts;

	private final PostViews views;

	private final FeedMedia media;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	ContributionService(JdbcClient jdbc, FeedAccess access, PostService posts, PostViews views, FeedMedia media,
			RateLimiter limiter, AuditLog audit, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.posts = posts;
		this.views = views;
		this.media = media;
		this.limiter = limiter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	/** What a contributor sends. The "no unconsented student appears" confirmation is not theirs to give. */
	public record ContributionCommand(String body, String bodyLanguageCode, List<FeedMediaRef> media, List<UUID> studentIds) {

		public ContributionCommand {
			media = (media == null) ? List.of() : List.copyOf(media);
			studentIds = (studentIds == null) ? List.of() : List.copyOf(new LinkedHashSet<>(studentIds));
		}

	}

	public record ApproveCommand(boolean confirmNoUnconsentedStudents, List<UUID> studentIds) {
	}

	@Transactional
	public PostView contribute(CurrentUser user, UUID schoolId, UUID classId, ContributionCommand cmd) {
		this.access.requireSchool(user, schoolId);
		ClassRef ref = this.access.viewableClass(user, schoolId, classId);
		boolean student = this.access.isActiveStudentOfClass(user, classId);
		FeedAuthorRole role = student ? FeedAuthorRole.STUDENT : FeedAuthorRole.GUARDIAN;
		Set<UUID> own = this.access.contributionStudents(user, schoolId, classId);
		if (student) {
			if (!this.access.settings().studentContributions(schoolId)) {
				throw ApiException.forbidden("feed.contributions_disabled");
			}
			if (!cmd.studentIds().isEmpty()) {
				throw ApiException.badRequest("feed.contribution_tag_not_own");
			}
		}
		else {
			if (!this.access.settings().parentContributions(schoolId)) {
				throw ApiException.forbidden("feed.contributions_disabled");
			}
			if (own.isEmpty()) {
				throw ApiException.forbidden("feed.contribution_not_allowed");
			}
			if (!own.containsAll(cmd.studentIds())) {
				throw ApiException.badRequest("feed.contribution_tag_not_own");
			}
			if (!cmd.media().isEmpty() && !this.access.settings().parentMedia(schoolId).contains(MediaKind.IMAGE)) {
				throw ApiException.forbidden("feed.contribution_media_not_allowed");
			}
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		this.limiter.consume("feed-contribution-user", user.userId().toString(), CONTRIBUTIONS_PER_DAY, Duration.ofDays(1),
				"feed.rate_limited");
		boolean approval = student || this.access.settings().parentApprovalRequired(schoolId);
		if (!approval && !cmd.media().isEmpty() && cmd.studentIds().isEmpty()) {
			throw ApiException.badRequest("feed.contribution_needs_tag");
		}
		PostCommand command = new PostCommand(cmd.body(), cmd.bodyLanguageCode(), null, cmd.media(), cmd.studentIds(), null,
				PublishMode.DRAFT, null, false);
		UUID id = this.posts.insert(user, FeedScope.CLASS, schoolId, classId, role, true, command);
		PostRow row = this.views.find(schoolId, id);
		if (approval) {
			this.jdbc.sql("update posts set status = 'PENDING_APPROVAL', updated_at = :now, version = version + 1 where id = :id")
				.param("now", Timestamp.from(this.clock.instant()))
				.param("id", id)
				.update();
			this.events.publishEvent(new ContributionSubmitted(id, schoolId, classId, user.userId()));
		}
		else {
			this.posts.publishRow(user, row, false);
		}
		return this.views.one(user, this.views.find(schoolId, id));
	}

	/** Who may use the approval queue of a class (or, without a class, of the school). */
	private void requireModerator(CurrentUser user, UUID schoolId, UUID classId) {
		if (!this.access.moderatesClass(user, schoolId, classId)) {
			throw ApiException.forbidden("feed.access_denied");
		}
	}

	/** Pending contributions the caller may approve, oldest first (keyset on the post id). */
	@Transactional(readOnly = true)
	public CursorPage<PostView> queue(CurrentUser user, UUID schoolId, UUID classId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		Set<UUID> moderated = new LinkedHashSet<>();
		if (classId != null) {
			this.access.viewableClass(user, schoolId, classId);
			requireModerator(user, schoolId, classId);
			moderated.add(classId);
		}
		else {
			if (!mayUseSchoolQueue(user, schoolId)) {
				throw ApiException.forbidden("feed.access_denied");
			}
			List<UUID> candidates = this.jdbc.sql("""
					select distinct class_id from posts
					where school_id = :s and status = 'PENDING_APPROVAL' and deleted_at is null and class_id is not null""")
				.param("s", schoolId)
				.query(UUID.class)
				.list();
			for (UUID candidate : candidates) {
				if (this.access.moderatesClass(user, schoolId, candidate)) {
					moderated.add(candidate);
				}
			}
		}
		int size = Cursors.clampLimit(limit);
		if (moderated.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		UUID after = (cursor == null) ? null : parseId(cursor);
		List<PostRow> rows = this.jdbc.sql("select " + FeedRows.POST_COLUMNS + " " + """
				from posts p where p.school_id = :s and p.class_id in (:classes) and p.status = 'PENDING_APPROVAL'
				   and p.deleted_at is null and (cast(:after as uuid) is null or p.id > cast(:after as uuid))
				 order by p.id limit :n""")
			.param("s", schoolId)
			.param("classes", moderated)
			.param("after", after)
			.param("n", size + 1)
			.query(FeedRows::post)
			.list();
		CursorPage<PostRow> page = CursorPage.fromOverfetch(rows, size, (r) -> Cursors.encode(r.id().toString()));
		return new CursorPage<>(this.views.map(user, page.items()), page.nextCursor());
	}

	/** The school-wide queue: school feed managers and teachers who lead a class of the school (others use a class's queue). */
	private boolean mayUseSchoolQueue(CurrentUser user, UUID schoolId) {
		if (this.access.feedManager(user, schoolId)) {
			return true;
		}
		return this.jdbc.sql("""
				select exists (select 1 from class_teachers where school_id = :s and teacher_id = :u and role = 'LEAD'
				               and status = 'ACTIVE')""")
			.param("s", schoolId)
			.param("u", user.userId())
			.query(Boolean.class)
			.single();
	}

	private static UUID parseId(String cursor) {
		try {
			return UUID.fromString(Cursors.decode(cursor, 1).getFirst());
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

	@Transactional
	public PostView approve(CurrentUser user, UUID schoolId, UUID postId, ApproveCommand cmd) {
		PostRow row = pendingForDecision(user, schoolId, postId);
		if (cmd.studentIds() != null) {
			this.posts.validateTags(FeedScope.CLASS, schoolId, row.classId(), cmd.studentIds());
			this.posts.replaceTags(postId, new LinkedHashSet<>(cmd.studentIds()));
		}
		this.posts.publishApproved(user, row, cmd.confirmNoUnconsentedStudents());
		this.jdbc.sql("update posts set decided_by = :u, decided_at = :now where id = :id")
			.param("u", user.userId())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", postId)
			.update();
		this.audit.record(AuditEntry.of("feed.contribution.approve", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("contributorRole", row.authorRole().name())
			.metadata("classId", row.classId().toString())
			.metadata("confirmed", cmd.confirmNoUnconsentedStudents()));
		this.events.publishEvent(new ContributionDecided(postId, schoolId, row.classId(), row.authorId(), user.userId(), true));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	@Transactional
	public PostView reject(CurrentUser user, UUID schoolId, UUID postId, String reason) {
		PostRow row = pendingForDecision(user, schoolId, postId);
		List<UUID> mediaIds = this.media.mediaIdsOfPost(postId);
		this.jdbc.sql("""
				update posts set status = 'REJECTED', rejection_reason = :reason, decided_by = :u, decided_at = :now,
				       updated_at = :now, version = version + 1
				where id = :id""")
			.param("reason", reason.strip())
			.param("u", user.userId())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", postId)
			.update();
		// A rejected photo of a child is not kept.
		this.media.discard(mediaIds);
		this.audit.record(AuditEntry.of("feed.contribution.reject", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("contributorRole", row.authorRole().name())
			.metadata("classId", row.classId().toString()));
		this.events.publishEvent(new ContributionDecided(postId, schoolId, row.classId(), row.authorId(), user.userId(), false));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	private PostRow pendingForDecision(CurrentUser user, UUID schoolId, UUID postId) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		if (!row.contribution() || row.classId() == null || !this.access.moderatesClass(user, schoolId, row.classId())) {
			throw ApiException.forbidden("feed.access_denied");
		}
		if (row.status() != PostStatus.PENDING_APPROVAL) {
			throw ApiException.conflict("feed.not_pending");
		}
		return row;
	}

}
