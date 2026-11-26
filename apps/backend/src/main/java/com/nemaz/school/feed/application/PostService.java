package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.tx.AdvisoryLocks;
import com.nemaz.school.feed.PostPublishBlocked;
import com.nemaz.school.feed.PostPublished;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedMediaRef;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedModels.PostStatus;
import com.nemaz.school.feed.application.FeedModels.PostType;
import com.nemaz.school.feed.application.FeedModels.PostView;
import com.nemaz.school.feed.application.FeedModels.PublishMode;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.media.MediaContext;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts: create (draft, publish now or schedule), edit, publish, archive, hide, delete and pin. Every state change is
 * audited in its transaction and publishes its event there. The consent gate ({@link ConsentWithholding#check}) runs
 * whenever content with media becomes visible.
 *
 * <p>
 * <b>State machine:</b> DRAFT, SCHEDULED, ARCHIVED (and HIDDEN, by a moderator) become PUBLISHED through
 * {@link #publish} (or the scheduler); PUBLISHED becomes ARCHIVED (author or moderator), HIDDEN (moderator) or deleted;
 * a contribution goes PENDING_APPROVAL, then PUBLISHED or REJECTED ({@link ContributionService}). A scheduled post whose
 * gate fails at its time returns to DRAFT with {@code publish_error_code} and its author is notified.
 */
@Service
public class PostService {

	static final int MAX_TAGS = 50;

	static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(90);

	private static final int SCHEDULER_BATCH = 200;

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final PostViews views;

	private final FeedMedia media;

	private final ConsentWithholding consent;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	PostService(JdbcClient jdbc, FeedAccess access, PostViews views, FeedMedia media, ConsentWithholding consent,
			AuditLog audit, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.views = views;
		this.media = media;
		this.consent = consent;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	/** What a client sends to create a post. Nulls mean the defaults. */
	public record PostCommand(String body, String bodyLanguageCode, PostType postType, List<FeedMediaRef> media,
			List<UUID> studentIds, Boolean commentsEnabled, PublishMode mode, Instant scheduledFor,
			boolean confirmNoUnconsentedStudents) {

		public PostCommand {
			media = (media == null) ? List.of() : List.copyOf(media);
			studentIds = (studentIds == null) ? List.of() : List.copyOf(new LinkedHashSet<>(studentIds));
		}

	}

	/** What a client sends to edit a post: a null field is left as it is; an empty body clears the text. */
	public record UpdateCommand(String body, String bodyLanguageCode, PostType postType, List<FeedMediaRef> media,
			List<UUID> studentIds, Boolean commentsEnabled, boolean confirmNoUnconsentedStudents) {

		public UpdateCommand {
			media = (media == null) ? null : List.copyOf(media);
			studentIds = (studentIds == null) ? null : List.copyOf(new LinkedHashSet<>(studentIds));
		}

	}

	// --- create ----------------------------------------------------------------------------

	@Transactional
	public PostView createClassPost(CurrentUser user, UUID schoolId, UUID classId, PostCommand cmd) {
		this.access.requireSchool(user, schoolId);
		ClassRef ref = this.access.viewableClass(user, schoolId, classId);
		if (!this.access.canPostToClass(user, ref)) {
			throw ApiException.forbidden("feed.access_denied");
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		UUID id = insert(user, FeedScope.CLASS, schoolId, classId, this.access.classAuthorRole(user, classId), false, cmd);
		return finish(user, id, cmd);
	}

	@Transactional
	public PostView createSchoolPost(CurrentUser user, UUID schoolId, PostCommand cmd) {
		this.access.requireSchool(user, schoolId);
		if (!this.access.feedManager(user, schoolId)) {
			throw ApiException.forbidden("feed.access_denied");
		}
		UUID id = insert(user, FeedScope.SCHOOL, schoolId, null, FeedAuthorRole.SCHOOL_ADMIN, false, cmd);
		return finish(user, id, cmd);
	}

	/** Writes the post row (DRAFT), its media and tags. Used by contributions too (they pass {@code contribution}). */
	UUID insert(CurrentUser user, FeedScope scope, UUID schoolId, UUID classId, FeedAuthorRole role, boolean contribution,
			PostCommand cmd) {
		validate(cmd);
		validateTags(scope, schoolId, classId, cmd.studentIds());
		UUID id = Ids.newId();
		Instant now = this.clock.instant();
		boolean hasMedia = !cmd.media().isEmpty();
		this.jdbc.sql("""
				insert into posts (id, scope_type, school_id, class_id, author_id, author_role, post_type, body,
				                   body_language_code, is_contribution, status, comments_enabled, created_at, updated_at)
				values (:id, :scope, :school, :class, :author, :role, :type, :body, :lang, :contribution, 'DRAFT', :comments,
				        :now, :now)""")
			.param("id", id)
			.param("scope", scope.name())
			.param("school", schoolId)
			.param("class", classId)
			.param("author", user.userId())
			.param("role", role.name())
			.param("type", (cmd.postType() != null ? cmd.postType() : PostType.POST).name())
			.param("body", FeedMedia.blankToNull(cmd.body()))
			.param("lang", FeedMedia.blankToNull(cmd.bodyLanguageCode()))
			.param("contribution", contribution)
			.param("comments", (cmd.commentsEnabled() != null) ? cmd.commentsEnabled() : this.access.settings().commentsDefault(schoolId))
			.param("now", Timestamp.from(now))
			.update();
		this.media.attachToPost(user, id, mediaContext(scope, schoolId, classId), cmd.media());
		replaceTags(id, cmd.studentIds());
		if (cmd.confirmNoUnconsentedStudents() && hasMedia) {
			storeConfirmation(user, id, schoolId, cmd.media().size(), cmd.studentIds().size());
		}
		this.audit.record(AuditEntry.of("feed.post.create", AuditOutcome.SUCCESS)
			.target("post", id)
			.school(schoolId)
			.metadata("scope", scope.name())
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("media", cmd.media().size())
			.metadata("tagged", cmd.studentIds().size())
			.metadata("contribution", contribution));
		return id;
	}

	private PostView finish(CurrentUser user, UUID id, PostCommand cmd) {
		PublishMode mode = (cmd.mode() != null) ? cmd.mode() : PublishMode.PUBLISH;
		PostRow row = this.views.find(schoolOf(id), id);
		switch (mode) {
			case DRAFT -> {
			}
			case PUBLISH -> publishRow(user, row, cmd.confirmNoUnconsentedStudents());
			case SCHEDULE -> scheduleRow(user, row, cmd.scheduledFor(), cmd.confirmNoUnconsentedStudents());
		}
		return this.views.one(user, this.views.find(row.schoolId(), id));
	}

	private UUID schoolOf(UUID postId) {
		return this.jdbc.sql("select school_id from posts where id = :id").param("id", postId).query(UUID.class).single();
	}

	static MediaContext mediaContext(FeedScope scope, UUID schoolId, UUID classId) {
		return (scope == FeedScope.CLASS) ? MediaContext.schoolClass(classId) : MediaContext.school(schoolId);
	}

	private void validate(PostCommand cmd) {
		boolean empty = (cmd.body() == null || cmd.body().isBlank()) && cmd.media().isEmpty();
		if (empty) {
			throw ApiException.badRequest("feed.post_empty");
		}
		FeedMedia.validate(cmd.media());
		validateLanguage(cmd.bodyLanguageCode());
	}

	void validateLanguage(String code) {
		if (code == null || code.isBlank()) {
			return;
		}
		boolean known = this.jdbc.sql("select exists (select 1 from languages where code = :c)")
			.param("c", code)
			.query(Boolean.class)
			.single();
		if (!known) {
			throw ApiException.badRequest("feed.translation_language_invalid");
		}
	}

	/** Tagged students must be ACTIVE in the class (class post) or the school (school-wide post). */
	void validateTags(FeedScope scope, UUID schoolId, UUID classId, Collection<UUID> tags) {
		if (tags.isEmpty()) {
			return;
		}
		if (tags.size() > MAX_TAGS) {
			throw ApiException.badRequest("feed.too_many_tags", MAX_TAGS);
		}
		Set<UUID> valid = (scope == FeedScope.CLASS) ? this.access.memberships().activeStudentIds(classId)
				: this.access.people().activeStudents(schoolId, tags);
		if (!valid.containsAll(tags)) {
			throw ApiException.badRequest("feed.tag_invalid");
		}
	}

	void replaceTags(UUID postId, Collection<UUID> tags) {
		this.jdbc.sql("delete from post_student_tags where post_id = :p").param("p", postId).update();
		for (UUID tag : tags) {
			this.jdbc.sql("insert into post_student_tags (post_id, student_id) values (:p, :s)")
				.param("p", postId)
				.param("s", tag)
				.update();
		}
	}

	// --- edit ------------------------------------------------------------------------------

	@Transactional
	public PostView update(CurrentUser user, UUID schoolId, UUID postId, UpdateCommand cmd) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		if (!row.authorId().equals(user.userId())) {
			throw ApiException.forbidden("feed.access_denied");
		}
		boolean editable = row.contribution() ? row.status() == PostStatus.PENDING_APPROVAL
				: (row.status() == PostStatus.DRAFT || row.status() == PostStatus.SCHEDULED
						|| row.status() == PostStatus.PENDING_APPROVAL || row.status() == PostStatus.PUBLISHED);
		if (!editable) {
			throw ApiException.conflict("feed.invalid_state");
		}
		List<UUID> tags = this.views.tagsOf(postId);
		boolean mediaChanged = false;
		if (cmd.media() != null) {
			FeedMedia.validate(cmd.media());
			mediaChanged = this.media.replaceOnPost(user, postId, mediaContext(row.scope(), row.schoolId(), row.classId()),
					cmd.media());
		}
		boolean tagsChanged = false;
		if (cmd.studentIds() != null) {
			validateTags(row.scope(), row.schoolId(), row.classId(), cmd.studentIds());
			tagsChanged = !new LinkedHashSet<>(tags).equals(new LinkedHashSet<>(cmd.studentIds()));
			if (tagsChanged) {
				replaceTags(postId, cmd.studentIds());
				tags = cmd.studentIds();
			}
		}
		String body = (cmd.body() != null) ? FeedMedia.blankToNull(cmd.body()) : row.body();
		int mediaCount = this.media.mediaIdsOfPost(postId).size();
		if (body == null && mediaCount == 0) {
			throw ApiException.badRequest("feed.post_empty");
		}
		validateLanguage(cmd.bodyLanguageCode());
		boolean changed = mediaChanged || tagsChanged;
		boolean confirmNow = cmd.confirmNoUnconsentedStudents() && mediaCount > 0;
		boolean confirmed = confirmNow || (row.mediaConfirmedAt() != null && !changed);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update posts set body = :body, body_language_code = :lang, post_type = :type, comments_enabled = :comments,
				       edited_at = :now, updated_at = :now, version = version + 1
				where id = :id""")
			.param("body", body)
			.param("lang", (cmd.bodyLanguageCode() != null) ? FeedMedia.blankToNull(cmd.bodyLanguageCode()) : row.bodyLanguage())
			.param("type", (cmd.postType() != null) ? cmd.postType().name() : row.postType().name())
			.param("comments", (cmd.commentsEnabled() != null) ? cmd.commentsEnabled() : row.commentsEnabled())
			.param("now", Timestamp.from(now))
			.param("id", postId)
			.update();
		if (changed) {
			// A confirmation covers the media and tags it was given for: it is dropped when either changes.
			this.jdbc.sql("update posts set media_confirmed_by = null, media_confirmed_at = null where id = :id")
				.param("id", postId)
				.update();
		}
		if (confirmNow) {
			storeConfirmation(user, postId, schoolId, mediaCount, tags.size());
		}
		if (row.status() == PostStatus.PUBLISHED && changed) {
			// An edit must not smuggle unconsented media into a published post.
			checkGate(user, row, tags, mediaCount > 0, confirmed);
		}
		this.audit.record(AuditEntry.of("feed.post.update", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("status", row.status().name())
			.metadata("mediaChanged", mediaChanged)
			.metadata("tagsChanged", tagsChanged));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	// --- publish, schedule ----------------------------------------------------------------

	@Transactional
	public PostView publish(CurrentUser user, UUID schoolId, UUID postId, boolean confirm) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		boolean moderator = this.access.moderates(user, row);
		if (!row.authorId().equals(user.userId()) && !moderator) {
			throw ApiException.forbidden("feed.access_denied");
		}
		boolean allowed = !row.contribution() && (row.status() == PostStatus.DRAFT || row.status() == PostStatus.SCHEDULED
				|| row.status() == PostStatus.ARCHIVED);
		if (!allowed) {
			throw ApiException.conflict("feed.invalid_state");
		}
		publishRow(user, row, confirm);
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	@Transactional
	public PostView schedule(CurrentUser user, UUID schoolId, UUID postId, Instant scheduledFor, boolean confirm) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		if (!row.authorId().equals(user.userId())) {
			throw ApiException.forbidden("feed.access_denied");
		}
		if (row.contribution() || (row.status() != PostStatus.DRAFT && row.status() != PostStatus.SCHEDULED)) {
			throw ApiException.conflict("feed.invalid_state");
		}
		scheduleRow(user, row, scheduledFor, confirm);
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	/** Back to DRAFT (cancels a schedule). */
	@Transactional
	public PostView unschedule(CurrentUser user, UUID schoolId, UUID postId) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		if (!row.authorId().equals(user.userId())) {
			throw ApiException.forbidden("feed.access_denied");
		}
		if (row.status() != PostStatus.SCHEDULED) {
			throw ApiException.conflict("feed.invalid_state");
		}
		setStatus(postId, "DRAFT", null);
		this.audit.record(AuditEntry.of("feed.post.unschedule", AuditOutcome.SUCCESS).target("post", postId).school(schoolId));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	private void scheduleRow(CurrentUser user, PostRow row, Instant scheduledFor, boolean confirm) {
		Instant now = this.clock.instant();
		if (scheduledFor == null || !scheduledFor.isAfter(now)) {
			throw ApiException.badRequest("feed.schedule_in_past");
		}
		if (scheduledFor.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
			throw ApiException.badRequest("feed.schedule_too_far");
		}
		List<UUID> tags = this.views.tagsOf(row.id());
		boolean hasMedia = !this.media.mediaIdsOfPost(row.id()).isEmpty();
		boolean confirmed = confirmed(row, confirm);
		// Early warning for the author; the gate runs again when the scheduler publishes.
		checkGate(user, row, tags, hasMedia, confirmed);
		if (confirm && hasMedia) {
			storeConfirmation(user, row.id(), row.schoolId(), mediaCountOf(row.id()), tags.size());
		}
		this.jdbc.sql("""
				update posts set status = 'SCHEDULED', scheduled_for = :at, publish_error_code = null, updated_at = :now,
				       version = version + 1
				where id = :id""")
			.param("at", Timestamp.from(scheduledFor))
			.param("now", Timestamp.from(now))
			.param("id", row.id())
			.update();
		this.audit.record(AuditEntry.of("feed.post.schedule", AuditOutcome.SUCCESS)
			.target("post", row.id())
			.school(row.schoolId())
			.metadata("scheduledFor", scheduledFor.toString()));
	}

	private boolean confirmed(PostRow row, boolean confirmNow) {
		return confirmNow || row.mediaConfirmedAt() != null;
	}

	/** Makes the post visible now (gate first). The row must be locked and in a publishable state. */
	void publishRow(CurrentUser actor, PostRow row, boolean confirm) {
		List<UUID> tags = this.views.tagsOf(row.id());
		boolean hasMedia = !this.media.mediaIdsOfPost(row.id()).isEmpty();
		checkGate(actor, row, tags, hasMedia, confirmed(row, confirm));
		if (confirm && hasMedia && row.mediaConfirmedAt() == null) {
			storeConfirmation(actor, row.id(), row.schoolId(), mediaCountOf(row.id()), tags.size());
		}
		markPublished(actor.userId(), row);
	}

	/** Used by the scheduler, which has no user: the stored confirmation counts, names are never disclosed. */
	private void markPublished(UUID actorId, PostRow row) {
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update posts set status = 'PUBLISHED', published_at = coalesce(published_at, :now), scheduled_for = null,
				       publish_error_code = null, rejection_reason = null, updated_at = :now, version = version + 1
				where id = :id""")
			.param("now", Timestamp.from(now))
			.param("id", row.id())
			.update();
		this.audit.record(AuditEntry.of("feed.post.publish", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("post", row.id())
			.school(row.schoolId())
			.metadata("scope", row.scope().name())
			.metadata("from", row.status().name()));
		this.events.publishEvent(new PostPublished(row.id(), row.schoolId(), row.classId(), row.authorId(), row.contribution()));
	}

	/** Publishes a contribution the approver accepted: same gate, with the approver's confirmation. */
	void publishApproved(CurrentUser approver, PostRow row, boolean confirm) {
		List<UUID> tags = this.views.tagsOf(row.id());
		boolean hasMedia = !this.media.mediaIdsOfPost(row.id()).isEmpty();
		checkGate(approver, row, tags, hasMedia, confirm);
		if (confirm && hasMedia) {
			storeConfirmation(approver, row.id(), row.schoolId(), mediaCountOf(row.id()), tags.size());
		}
		markPublished(approver.userId(), row);
	}

	void checkGate(CurrentUser actor, PostRow row, Collection<UUID> tags, boolean hasMedia, boolean confirmed) {
		boolean disclose = actor != null && ((row.scope() == FeedScope.CLASS)
				? this.access.classes().canViewRoster(actor, row.classId()) : this.access.feedManager(actor, row.schoolId()));
		this.consent.check(row.schoolId(), tags, hasMedia, confirmed, disclose);
	}

	/** Remembers an explicit confirmation of the post's media and audits it (who, how many photos, how many tagged). */
	private void storeConfirmation(CurrentUser actor, UUID postId, UUID schoolId, int media, int tagged) {
		this.jdbc.sql("update posts set media_confirmed_by = :u, media_confirmed_at = :now where id = :id")
			.param("u", actor.userId())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", postId)
			.update();
		this.audit.record(AuditEntry.of("feed.post.media_confirmed", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("media", media)
			.metadata("tagged", tagged)
			.metadata("statement", "no student without media-publication consent appears"));
	}

	private int mediaCountOf(UUID postId) {
		return this.media.mediaIdsOfPost(postId).size();
	}

	// --- scheduler -------------------------------------------------------------------------

	/** IDs of posts due at or before now. */
	List<UUID> dueIds() {
		return this.jdbc.sql("""
				select id from posts where status = 'SCHEDULED' and deleted_at is null and scheduled_for <= :now
				order by scheduled_for, id limit :n""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("n", SCHEDULER_BATCH)
			.query(UUID.class)
			.list();
	}

	/**
	 * Publishes one due post (idempotent: a post that is no longer SCHEDULED or not yet due is skipped). The gate runs
	 * again with the stored confirmation; when it fails the post returns to DRAFT with the error code and its author is
	 * notified through {@link PostPublishBlocked}. Returns true when the post was published.
	 */
	@Transactional
	public boolean publishDue(UUID postId) {
		PostRow row = this.jdbc.sql("select " + FeedRows.POST_COLUMNS + " from posts p where p.id = :id and p.deleted_at is null for update skip locked")
			.param("id", postId)
			.query(FeedRows::post)
			.optional()
			.orElse(null);
		if (row == null || row.status() != PostStatus.SCHEDULED || row.scheduledFor() == null
				|| row.scheduledFor().isAfter(this.clock.instant())) {
			return false;
		}
		List<UUID> tags = this.views.tagsOf(row.id());
		boolean hasMedia = !this.media.mediaIdsOfPost(row.id()).isEmpty();
		try {
			this.consent.check(row.schoolId(), tags, hasMedia, row.mediaConfirmedAt() != null, false);
		}
		catch (ApiException ex) {
			setStatus(row.id(), "DRAFT", ex.code());
			this.audit.record(AuditEntry.of("feed.post.publish", AuditOutcome.FAILURE)
				.actor(null)
				.target("post", row.id())
				.school(row.schoolId())
				.metadata("code", ex.code())
				.metadata("scheduled", true));
			this.events.publishEvent(new PostPublishBlocked(row.id(), row.schoolId(), row.classId(), row.authorId(), ex.code()));
			return false;
		}
		markPublished(null, row);
		return true;
	}

	private void setStatus(UUID postId, String status, String errorCode) {
		this.jdbc.sql("""
				update posts set status = :status, scheduled_for = case when :status = 'SCHEDULED' then scheduled_for end,
				       publish_error_code = :code, updated_at = :now, version = version + 1
				where id = :id""")
			.param("status", status)
			.param("code", errorCode)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", postId)
			.update();
	}

	// --- archive, hide, delete, pin -------------------------------------------------------

	@Transactional
	public PostView archive(CurrentUser user, UUID schoolId, UUID postId) {
		PostRow row = lockedForChange(user, schoolId, postId, false);
		if (row.status() != PostStatus.PUBLISHED) {
			throw ApiException.conflict("feed.invalid_state");
		}
		takeDown(row, "ARCHIVED", null);
		this.audit.record(AuditEntry.of("feed.post.archive", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("moderation", !row.authorId().equals(user.userId())));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	/** A moderator hides a published post from families; the reason is shown to the author. */
	@Transactional
	public PostView hide(CurrentUser user, UUID schoolId, UUID postId, String reason) {
		PostRow row = lockedForChange(user, schoolId, postId, true);
		if (row.status() != PostStatus.PUBLISHED) {
			throw ApiException.conflict("feed.invalid_state");
		}
		takeDown(row, "HIDDEN", FeedMedia.blankToNull(reason));
		this.audit.record(AuditEntry.of("feed.post.hide", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("authorId", row.authorId().toString())
			.metadata("hasReason", reason != null && !reason.isBlank()));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	@Transactional
	public PostView unhide(CurrentUser user, UUID schoolId, UUID postId, boolean confirm) {
		PostRow row = lockedForChange(user, schoolId, postId, true);
		if (row.status() != PostStatus.HIDDEN) {
			throw ApiException.conflict("feed.invalid_state");
		}
		publishRow(user, row, confirm);
		this.audit.record(AuditEntry.of("feed.post.unhide", AuditOutcome.SUCCESS).target("post", postId).school(schoolId));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

	@Transactional
	public void delete(CurrentUser user, UUID schoolId, UUID postId) {
		PostRow row = lockedForChange(user, schoolId, postId, false);
		List<UUID> mediaIds = this.media.mediaIdsOfPost(postId);
		this.jdbc.sql("""
				update posts set deleted_at = :now, deleted_by = :u, is_pinned = false, pinned_at = null, scheduled_for = null,
				       updated_at = :now, version = version + 1
				where id = :id""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("u", user.userId())
			.param("id", postId)
			.update();
		this.media.discard(mediaIds);
		this.audit.record(AuditEntry.of("feed.post.delete", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("moderation", !row.authorId().equals(user.userId()))
			.metadata("status", row.status().name())
			.metadata("media", mediaIds.size()));
	}

	/**
	 * Locks the post for a change by its author or a moderator ({@code moderatorOnly}: moderators only). 404 when the
	 * caller cannot read it, 403 when they can but may not change it.
	 */
	private PostRow lockedForChange(CurrentUser user, UUID schoolId, UUID postId, boolean moderatorOnly) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		boolean moderator = this.access.moderates(user, row);
		boolean author = row.authorId().equals(user.userId());
		if (!(moderator || (author && !moderatorOnly))) {
			throw ApiException.forbidden("feed.access_denied");
		}
		return row;
	}

	private void takeDown(PostRow row, String status, String reason) {
		this.jdbc.sql("""
				update posts set status = :status, is_pinned = false, pinned_at = null, rejection_reason = :reason,
				       updated_at = :now, version = version + 1
				where id = :id""")
			.param("status", status)
			.param("reason", reason)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", row.id())
			.update();
	}

	private static java.util.Map<String, Object> pinKey(PostRow row) {
		java.util.Map<String, Object> params = new java.util.HashMap<>();
		params.put("scope", row.scope().name());
		params.put("school", row.schoolId());
		if (row.classId() != null) {
			params.put("class", row.classId());
		}
		return params;
	}

	@Transactional
	public PostView pin(CurrentUser user, UUID schoolId, UUID postId, boolean pinned) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.lock(schoolId, postId);
		if (!this.views.readable(user, row)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		boolean moderator = this.access.moderates(user, row);
		boolean teacherAuthor = row.authorId().equals(user.userId())
				&& (row.authorRole() == FeedAuthorRole.TEACHER || row.authorRole() == FeedAuthorRole.SCHOOL_ADMIN);
		if (!moderator && !teacherAuthor) {
			throw ApiException.forbidden("feed.access_denied");
		}
		if (pinned && !row.published()) {
			throw ApiException.conflict("feed.pin_not_published");
		}
		if (pinned && !row.pinned()) {
			// Serialize the count and the insert of pins of one class (or of the school feed).
			AdvisoryLocks.acquire(this.jdbc, "feed.pin:" + ((row.classId() != null) ? row.classId() : "school-" + row.schoolId()));
			int limit = this.access.settings().pinLimit(row.schoolId());
			int count = this.jdbc.sql("select count(*) from posts where is_pinned and status = 'PUBLISHED' and deleted_at is null"
					+ " and scope_type = :scope and school_id = :school and "
					+ ((row.classId() != null) ? "class_id = :class" : "class_id is null"))
				.params(pinKey(row))
				.query(Integer.class)
				.single();
			if (count >= limit) {
				throw ApiException.conflict("feed.pin_limit_reached", limit);
			}
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("update posts set is_pinned = :pinned, pinned_at = :at, updated_at = :now, version = version + 1 where id = :id")
			.param("pinned", pinned)
			.param("at", pinned ? Timestamp.from(now) : null)
			.param("now", Timestamp.from(now))
			.param("id", postId)
			.update();
		this.audit.record(AuditEntry.of(pinned ? "feed.post.pin" : "feed.post.unpin", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId));
		return this.views.one(user, this.views.find(schoolId, postId));
	}

}
