package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.StoryPublished;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedMediaItem;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedModels.FeedStudentTag;
import com.nemaz.school.feed.application.FeedModels.StoryView;
import com.nemaz.school.feed.application.FeedRows.StoryRow;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaPurpose;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stories: a photo and/or a short text that families can see for 24 hours. They are written by teachers (class stories)
 * and school feed managers (school stories) and are visible under the same rules as posts ({@link FeedVisibility}) with
 * two additions: the story must not be past {@code expires_at} at the moment of the request (so a late expiry job can
 * never leak one), and it passes the same consent gate and withholding as a post. A story whose photo is withheld
 * because of a tagged student's consent is left out of the lists of everybody but its author and the moderators.
 * After expiry nobody but the author and the moderators can still read the media, until the expiry job deletes it
 * seven days later.
 */
@Service
public class StoryService {

	static final Duration LIFETIME = Duration.ofHours(24);

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final FeedMedia media;

	private final ConsentWithholding consent;

	private final PostService posts;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	StoryService(JdbcClient jdbc, FeedAccess access, FeedVisibility visibility, FeedMedia media,
			ConsentWithholding consent, PostService posts, AuditLog audit, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.media = media;
		this.consent = consent;
		this.posts = posts;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public record StoryCommand(String caption, String captionLanguageCode, UUID mediaId, List<UUID> studentIds,
			boolean confirmNoUnconsentedStudents) {

		public StoryCommand {
			studentIds = (studentIds == null) ? List.of() : List.copyOf(new LinkedHashSet<>(studentIds));
		}

	}

	// --- create and delete -------------------------------------------------------------------

	@Transactional
	public StoryView createClassStory(CurrentUser user, UUID schoolId, UUID classId, StoryCommand cmd) {
		this.access.requireSchool(user, schoolId);
		ClassRef ref = this.access.viewableClass(user, schoolId, classId);
		if (!this.access.canPostToClass(user, ref)) {
			throw ApiException.forbidden("feed.access_denied");
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		return create(user, FeedScope.CLASS, schoolId, classId, this.access.classAuthorRole(user, classId), cmd);
	}

	@Transactional
	public StoryView createSchoolStory(CurrentUser user, UUID schoolId, StoryCommand cmd) {
		this.access.requireSchool(user, schoolId);
		if (!this.access.feedManager(user, schoolId)) {
			throw ApiException.forbidden("feed.access_denied");
		}
		return create(user, FeedScope.SCHOOL, schoolId, null, FeedAuthorRole.SCHOOL_ADMIN, cmd);
	}

	private StoryView create(CurrentUser user, FeedScope scope, UUID schoolId, UUID classId, FeedAuthorRole role,
			StoryCommand cmd) {
		String caption = FeedMedia.blankToNull(cmd.caption());
		if (caption == null && cmd.mediaId() == null) {
			throw ApiException.badRequest("feed.story_empty");
		}
		this.posts.validateLanguage(cmd.captionLanguageCode());
		this.posts.validateTags(scope, schoolId, classId, cmd.studentIds());
		boolean hasMedia = cmd.mediaId() != null;
		if (hasMedia) {
			this.media.requireUsable(user, cmd.mediaId(), MediaPurpose.STORY, PostService.mediaContext(scope, schoolId, classId));
		}
		boolean disclose = (scope == FeedScope.CLASS) ? this.access.classes().canViewRoster(user, classId)
				: this.access.feedManager(user, schoolId);
		this.consent.check(schoolId, cmd.studentIds(), hasMedia, cmd.confirmNoUnconsentedStudents(), disclose);
		UUID id = Ids.newId();
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				insert into stories (id, scope_type, school_id, class_id, author_id, author_role, media_id, caption,
				                     caption_language_code, status, published_at, expires_at, media_confirmed_by,
				                     media_confirmed_at, created_at, updated_at)
				values (:id, :scope, :school, :class, :author, :role, :media, :caption, :lang, 'PUBLISHED', :now, :expires,
				        :confirmedBy, :confirmedAt, :now, :now)""")
			.param("id", id)
			.param("scope", scope.name())
			.param("school", schoolId)
			.param("class", classId)
			.param("author", user.userId())
			.param("role", role.name())
			.param("media", cmd.mediaId())
			.param("caption", caption)
			.param("lang", FeedMedia.blankToNull(cmd.captionLanguageCode()))
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(now.plus(LIFETIME)))
			.param("confirmedBy", (cmd.confirmNoUnconsentedStudents() && hasMedia) ? user.userId() : null)
			.param("confirmedAt", (cmd.confirmNoUnconsentedStudents() && hasMedia) ? Timestamp.from(now) : null)
			.update();
		for (UUID tag : cmd.studentIds()) {
			this.jdbc.sql("insert into story_student_tags (story_id, student_id) values (:s, :t)").param("s", id).param("t", tag).update();
		}
		this.audit.record(AuditEntry.of("feed.story.create", AuditOutcome.SUCCESS)
			.target("story", id)
			.school(schoolId)
			.metadata("scope", scope.name())
			.metadata("media", hasMedia)
			.metadata("tagged", cmd.studentIds().size()));
		if (cmd.confirmNoUnconsentedStudents() && hasMedia) {
			this.audit.record(AuditEntry.of("feed.story.media_confirmed", AuditOutcome.SUCCESS)
				.target("story", id)
				.school(schoolId)
				.metadata("tagged", cmd.studentIds().size())
				.metadata("statement", "no student without media-publication consent appears"));
		}
		this.events.publishEvent(new StoryPublished(id, schoolId, classId, user.userId()));
		return map(user, List.of(find(schoolId, id))).getFirst();
	}

	@Transactional
	public void delete(CurrentUser user, UUID schoolId, UUID storyId) {
		this.access.requireSchool(user, schoolId);
		StoryRow row = lock(schoolId, storyId);
		FeedViewer viewer = new FeedViewer(user, this.access);
		boolean author = row.authorId().equals(user.userId());
		boolean moderator = viewer.moderates(row.scope(), row.schoolId(), row.classId());
		if (!author && !moderator) {
			if (this.visibility.canSeeStory(user, storyId)) {
				throw ApiException.forbidden("feed.access_denied");
			}
			throw ApiException.notFound("feed.story_not_found");
		}
		this.jdbc.sql("""
				update stories set status = 'DELETED', deleted_at = :now, updated_at = :now, version = version + 1
				where id = :id""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", storyId)
			.update();
		if (row.mediaId() != null) {
			this.media.discard(List.of(row.mediaId()));
		}
		this.audit.record(AuditEntry.of("feed.story.delete", AuditOutcome.SUCCESS)
			.target("story", storyId)
			.school(schoolId)
			.metadata("moderation", !author));
	}

	// --- reads -------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public StoryView story(CurrentUser user, UUID schoolId, UUID storyId) {
		this.access.requireSchool(user, schoolId);
		StoryRow row = find(schoolId, storyId);
		if (!readable(user, row)) {
			throw ApiException.notFound("feed.story_not_found");
		}
		return map(user, List.of(row)).getFirst();
	}

	@Transactional(readOnly = true)
	public List<StoryView> classStories(CurrentUser user, UUID schoolId, UUID classId) {
		this.access.requireSchool(user, schoolId);
		this.access.viewableClass(user, schoolId, classId);
		SqlFragment visible = this.visibility.visibleStories(user, "s");
		List<StoryRow> rows = this.jdbc.sql("select " + FeedRows.STORY_COLUMNS
				+ " from stories s where s.scope_type = 'CLASS' and s.school_id = :school and s.class_id = :class and ("
				+ visible.sql() + ") order by s.published_at desc, s.id desc limit 100")
			.param("school", schoolId)
			.param("class", classId)
			.params(visible.params())
			.query(FeedRows::story)
			.list();
		return map(user, rows);
	}

	@Transactional(readOnly = true)
	public List<StoryView> schoolStories(CurrentUser user, UUID schoolId) {
		this.access.requireSchool(user, schoolId);
		this.access.requireSchoolMember(user, schoolId);
		SqlFragment visible = this.visibility.visibleStories(user, "s");
		List<StoryRow> rows = this.jdbc.sql("select " + FeedRows.STORY_COLUMNS
				+ " from stories s where s.scope_type = 'SCHOOL' and s.school_id = :school and (" + visible.sql()
				+ ") order by s.published_at desc, s.id desc limit 100")
			.param("school", schoolId)
			.params(visible.params())
			.query(FeedRows::story)
			.list();
		return map(user, rows);
	}

	/** Active stories of the classes and schools of the caller's children (or the caller, a student), newest first. */
	@Transactional(readOnly = true)
	public List<StoryView> familyStories(CurrentUser user, UUID studentId) {
		Set<UUID> kids = new LinkedHashSet<>();
		for (GuardianGrant grant : this.access.guardians().childrenOf(user.userId())) {
			if (grant.allows(GuardianPermission.VIEW_ACADEMICS)) {
				kids.add(grant.studentId());
			}
		}
		boolean student = this.jdbc.sql("select exists (select 1 from school_students where student_id = :u and status = 'ACTIVE')")
			.param("u", user.userId())
			.query(Boolean.class)
			.single();
		if (student) {
			kids.add(user.userId());
		}
		if (studentId != null) {
			if (!kids.contains(studentId)) {
				throw ApiException.notFound("student.not_found");
			}
			kids = Set.of(studentId);
		}
		if (kids.isEmpty()) {
			return List.of();
		}
		List<UUID> schools = this.jdbc
			.sql("select distinct school_id from school_students where student_id in (:kids) and status = 'ACTIVE'")
			.param("kids", kids)
			.query(UUID.class)
			.list()
			.stream()
			.filter(this.access::featureEnabled)
			.toList();
		if (schools.isEmpty()) {
			return List.of();
		}
		SqlFragment visible = this.visibility.visibleStories(user, "s");
		Map<String, Object> params = new HashMap<>(visible.params());
		params.put("kids", kids);
		params.put("schools", schools);
		List<StoryRow> rows = this.jdbc.sql("select " + FeedRows.STORY_COLUMNS
				+ " from stories s where s.school_id in (:schools) and (" + visible.sql() + ") and ("
				+ "(s.scope_type = 'CLASS' and exists (select 1 from class_students fc where fc.class_id = s.class_id"
				+ " and fc.student_id in (:kids) and fc.status = 'ACTIVE'))"
				+ " or (s.scope_type = 'SCHOOL' and exists (select 1 from school_students fs where fs.school_id = s.school_id"
				+ " and fs.student_id in (:kids) and fs.status = 'ACTIVE'))) order by s.published_at desc, s.id desc limit 100")
			.params(params)
			.query(FeedRows::story)
			.list();
		return map(user, rows);
	}

	/** Records that the caller saw the story (one row per viewer; the author's own views are not counted). */
	@Transactional
	public void view(CurrentUser user, UUID schoolId, UUID storyId) {
		this.access.requireSchool(user, schoolId);
		StoryRow row = find(schoolId, storyId);
		if (!this.visibility.canSeeStory(user, storyId)) {
			throw ApiException.notFound("feed.story_not_found");
		}
		if (this.consent.storyWithheld(storyId) && !readablePrivileged(user, row)) {
			throw ApiException.notFound("feed.story_not_found");
		}
		this.jdbc.sql("""
				insert into story_views (story_id, user_id, viewed_at) values (:s, :u, :now)
				on conflict (story_id, user_id) do nothing""")
			.param("s", storyId)
			.param("u", user.userId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	// --- internals ---------------------------------------------------------------------------

	StoryRow find(UUID schoolId, UUID storyId) {
		return this.jdbc.sql("select " + FeedRows.STORY_COLUMNS + " from stories s where s.id = :id and s.school_id = :school and s.deleted_at is null")
			.param("id", storyId)
			.param("school", schoolId)
			.query(FeedRows::story)
			.optional()
			.orElseThrow(() -> ApiException.notFound("feed.story_not_found"));
	}

	private StoryRow lock(UUID schoolId, UUID storyId) {
		return this.jdbc.sql("select " + FeedRows.STORY_COLUMNS
				+ " from stories s where s.id = :id and s.school_id = :school and s.deleted_at is null for update")
			.param("id", storyId)
			.param("school", schoolId)
			.query(FeedRows::story)
			.optional()
			.orElseThrow(() -> ApiException.notFound("feed.story_not_found"));
	}

	private boolean readablePrivileged(CurrentUser user, StoryRow row) {
		return row.authorId().equals(user.userId()) || this.access.moderates(user, row.scope(), row.schoolId(), row.classId());
	}

	/** A single story is readable while visible (not expired, not withheld), and to its author and moderators in any state. */
	private boolean readable(CurrentUser user, StoryRow row) {
		if (readablePrivileged(user, row)) {
			return true;
		}
		return this.visibility.canSeeStory(user, row.id()) && !this.consent.storyWithheld(row.id());
	}

	private List<StoryView> map(CurrentUser user, List<StoryRow> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		FeedViewer viewer = new FeedViewer(user, this.access);
		List<UUID> ids = rows.stream().map(StoryRow::id).toList();
		Set<UUID> withheld = this.consent.withheldStories(rows.stream().filter((r) -> r.mediaId() != null).map(StoryRow::id).toList());
		Map<UUID, List<UUID>> tags = new HashMap<>();
		this.jdbc.sql("select story_id, student_id from story_student_tags where story_id in (:ids) order by story_id, student_id")
			.param("ids", ids)
			.query((rs, i) -> {
				tags.computeIfAbsent(Rows.uuid(rs, "story_id"), (k) -> new ArrayList<>()).add(Rows.uuid(rs, "student_id"));
				return 0;
			})
			.list();
		Set<UUID> viewed = new HashSet<>(this.jdbc.sql("select story_id from story_views where user_id = :u and story_id in (:ids)")
			.param("u", user.userId())
			.param("ids", ids)
			.query(UUID.class)
			.list());
		Map<UUID, Integer> counts = new HashMap<>();
		List<UUID> countable = rows.stream()
			.filter((r) -> r.authorId().equals(user.userId()) || viewer.staff(r.scope(), r.schoolId(), r.classId()))
			.map(StoryRow::id)
			.toList();
		if (!countable.isEmpty()) {
			this.jdbc.sql("""
					select sv.story_id, count(*) as n from story_views sv join stories st on st.id = sv.story_id
					where sv.story_id in (:ids) and sv.user_id <> st.author_id group by sv.story_id""")
				.param("ids", countable)
				.query((rs, i) -> {
					counts.put(Rows.uuid(rs, "story_id"), rs.getInt("n"));
					return 0;
				})
				.list();
		}
		Set<UUID> people = new HashSet<>();
		for (StoryRow row : rows) {
			if (viewer.canSeeName(row.authorRole(), row.authorId(), row.scope(), row.schoolId(), row.classId())) {
				people.add(row.authorId());
			}
			for (UUID tag : tags.getOrDefault(row.id(), List.of())) {
				if (viewer.canSeeTag(row.scope(), row.schoolId(), row.classId(), tag)) {
					people.add(tag);
				}
			}
		}
		Map<UUID, PersonCard> cards = this.access.people().cards(people);
		List<StoryView> views = new ArrayList<>();
		for (StoryRow row : rows) {
			boolean author = row.authorId().equals(user.userId());
			boolean moderator = viewer.moderates(row.scope(), row.schoolId(), row.classId());
			boolean isWithheld = row.mediaId() != null && withheld.contains(row.id());
			if (isWithheld && !author && !moderator) {
				continue;
			}
			FeedMediaItem item = this.media.itemOf(row.mediaId());
			List<FeedStudentTag> tagViews = new ArrayList<>();
			for (UUID tag : tags.getOrDefault(row.id(), List.of())) {
				PersonCard card = cards.get(tag);
				if (card != null && viewer.canSeeTag(row.scope(), row.schoolId(), row.classId(), tag)) {
					tagViews.add(new FeedStudentTag(tag, card.displayName(), card.avatar()));
				}
			}
			views.add(new StoryView(row.id(), row.scope(), row.schoolId(), row.classId(), row.status(),
					PostViews.author(viewer, row.authorRole(), row.authorId(), row.scope(), row.schoolId(), row.classId(), cards),
					row.caption(), row.captionLanguage(), item, isWithheld, tagViews, row.publishedAt(), row.expiresAt(),
					viewed.contains(row.id()), countable.contains(row.id()) ? counts.getOrDefault(row.id(), 0) : null,
					author || moderator));
		}
		return views;
	}

}
