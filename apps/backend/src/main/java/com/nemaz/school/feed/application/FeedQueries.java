package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.application.FeedModels.FeedCapabilities;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedModels.PostStatus;
import com.nemaz.school.feed.application.FeedModels.PostView;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.media.MediaKind;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The feeds. Every list is filtered in SQL by {@link FeedVisibility} (the same predicate the single-post read and the
 * media policy use) and paged by keyset: newest first, pinned posts first on class and school feeds. A cursor is
 * {@code (pinned, published_at, id)} (the family feed has no pins: {@code (published_at, id)}), so a page never repeats or
 * skips a post that was there when the first page was read; a post pinned or unpinned in between may appear on two pages
 * or none, and clients can ignore that.
 */
@Service
@Transactional(readOnly = true)
public class FeedQueries {

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final PostViews views;

	FeedQueries(JdbcClient jdbc, FeedAccess access, FeedVisibility visibility, PostViews views) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.views = views;
	}

	/** One post (published to those who may see it; any status to its author and the moderators). */
	public PostView post(CurrentUser user, UUID schoolId, UUID postId) {
		this.access.requireSchool(user, schoolId);
		return this.views.one(user, this.views.requireReadable(user, schoolId, postId));
	}

	public CursorPage<PostView> classFeed(CurrentUser user, UUID schoolId, UUID classId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		this.access.viewableClass(user, schoolId, classId);
		SqlFragment visible = this.visibility.visiblePosts(user, "p");
		StringBuilder where = new StringBuilder("p.scope_type = 'CLASS' and p.school_id = :school and p.class_id = :class and ("
				+ visible.sql() + ")");
		return pinnedFirst(user, where, visible, Map.of("school", schoolId, "class", classId), cursor, limit);
	}

	public CursorPage<PostView> schoolFeed(CurrentUser user, UUID schoolId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		this.access.requireSchoolMember(user, schoolId);
		SqlFragment visible = this.visibility.visiblePosts(user, "p");
		StringBuilder where = new StringBuilder("p.scope_type = 'SCHOOL' and p.school_id = :school and (" + visible.sql() + ")");
		return pinnedFirst(user, where, visible, Map.of("school", schoolId), cursor, limit);
	}

	private CursorPage<PostView> pinnedFirst(CurrentUser user, StringBuilder where, SqlFragment visible,
			Map<String, Object> params, String cursor, Integer limit) {
		int size = Cursors.clampLimit(limit);
		java.util.HashMap<String, Object> all = new java.util.HashMap<>(params);
		all.putAll(visible.params());
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 3);
			try {
				all.put("cur_pinned", "1".equals(parts.get(0)));
				all.put("cur_at", Timestamp.from(Instant.parse(parts.get(1))));
				all.put("cur_id", UUID.fromString(parts.get(2)));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
			where.append(" and (p.is_pinned, p.published_at, p.id) < (:cur_pinned, :cur_at, :cur_id)");
		}
		all.put("n", size + 1);
		List<PostRow> rows = this.jdbc.sql("select " + FeedRows.POST_COLUMNS + " from posts p where " + where
				+ " order by p.is_pinned desc, p.published_at desc, p.id desc limit :n")
			.params(all)
			.query(FeedRows::post)
			.list();
		CursorPage<PostRow> page = CursorPage.fromOverfetch(rows, size,
				(r) -> Cursors.encode(r.pinned() ? "1" : "0", r.publishedAt().toString(), r.id().toString()));
		return new CursorPage<>(this.views.map(user, page.items()), page.nextCursor());
	}

	/**
	 * The home feed of a family member: published posts of the classes their children are ACTIVE in and school-wide posts
	 * of those children's schools, newest first, optionally for one child. A student sees the same for themself. Children
	 * come from ACTIVE, not custody-restricted guardian links with {@code can_view_academics}; a restricted or revoked
	 * link contributes nothing. Schools whose feed is switched off are left out.
	 */
	public CursorPage<PostView> familyFeed(CurrentUser user, UUID studentId, String cursor, Integer limit) {
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
		int size = Cursors.clampLimit(limit);
		if (kids.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		List<UUID> schools = new ArrayList<>(this.jdbc
			.sql("select distinct school_id from school_students where student_id in (:kids) and status = 'ACTIVE'")
			.param("kids", kids)
			.query(UUID.class)
			.list()
			.stream()
			.filter(this.access::featureEnabled)
			.toList());
		if (schools.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		SqlFragment visible = this.visibility.visiblePosts(user, "p");
		java.util.HashMap<String, Object> params = new java.util.HashMap<>(visible.params());
		params.put("kids", kids);
		params.put("schools", schools);
		StringBuilder where = new StringBuilder("p.school_id in (:schools) and (" + visible.sql() + ") and (")
			.append("(p.scope_type = 'CLASS' and exists (select 1 from class_students fc where fc.class_id = p.class_id")
			.append(" and fc.student_id in (:kids) and fc.status = 'ACTIVE'))")
			.append(" or (p.scope_type = 'SCHOOL' and exists (select 1 from school_students fs where fs.school_id = p.school_id")
			.append(" and fs.student_id in (:kids) and fs.status = 'ACTIVE')))");
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				params.put("cur_at", Timestamp.from(Instant.parse(parts.get(0))));
				params.put("cur_id", UUID.fromString(parts.get(1)));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
			where.append(" and (p.published_at, p.id) < (:cur_at, :cur_id)");
		}
		params.put("n", size + 1);
		List<PostRow> rows = this.jdbc.sql("select " + FeedRows.POST_COLUMNS + " from posts p where " + where
				+ " order by p.published_at desc, p.id desc limit :n")
			.params(params)
			.query(FeedRows::post)
			.list();
		CursorPage<PostRow> page = CursorPage.fromOverfetch(rows, size,
				(r) -> Cursors.encode(r.publishedAt().toString(), r.id().toString()));
		return new CursorPage<>(this.views.map(user, page.items()), page.nextCursor());
	}

	/**
	 * The caller's own posts and contributions in a school, all statuses (drafts, scheduled, pending, rejected, ...),
	 * newest first. {@code status} and {@code classId} are optional filters.
	 */
	public CursorPage<PostView> myPosts(CurrentUser user, UUID schoolId, PostStatus status, UUID classId, String cursor,
			Integer limit) {
		this.access.requireSchool(user, schoolId);
		int size = Cursors.clampLimit(limit);
		java.util.HashMap<String, Object> params = new java.util.HashMap<>();
		params.put("school", schoolId);
		params.put("user", user.userId());
		StringBuilder where = new StringBuilder("p.school_id = :school and p.author_id = :user and p.deleted_at is null");
		if (status != null) {
			where.append(" and p.status = :status");
			params.put("status", status.name());
		}
		if (classId != null) {
			where.append(" and p.class_id = :class");
			params.put("class", classId);
		}
		if (cursor != null) {
			try {
				params.put("after", UUID.fromString(Cursors.decode(cursor, 1).getFirst()));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
			where.append(" and p.id < :after");
		}
		params.put("n", size + 1);
		List<PostRow> rows = this.jdbc.sql("select " + FeedRows.POST_COLUMNS + " from posts p where " + where
				+ " order by p.id desc limit :n")
			.params(params)
			.query(FeedRows::post)
			.list();
		CursorPage<PostRow> page = CursorPage.fromOverfetch(rows, size, (r) -> Cursors.encode(r.id().toString()));
		return new CursorPage<>(this.views.map(user, page.items()), page.nextCursor());
	}

	/** What the caller may do in the feed of the school (or of one class), for the clients' menus and buttons. */
	public FeedCapabilities capabilities(CurrentUser user, UUID schoolId, UUID classId) {
		this.access.requireSchool(user, schoolId);
		FeedSettings settings = this.access.settings();
		boolean manager = this.access.feedManager(user, schoolId);
		boolean canPost = false;
		boolean moderate = manager;
		Set<UUID> contributionStudents = Set.of();
		boolean studentContributor = false;
		UUID campusId = null;
		FeedScope scope = FeedScope.SCHOOL;
		if (classId != null) {
			ClassRef ref = this.access.viewableClass(user, schoolId, classId);
			campusId = ref.campusId();
			scope = FeedScope.CLASS;
			canPost = !ref.archived() && this.access.canPostToClass(user, ref);
			moderate = this.access.moderatesClass(user, schoolId, classId);
			contributionStudents = ref.archived() ? Set.of() : this.access.contributionStudents(user, schoolId, classId);
			studentContributor = this.access.studentContributor(user, schoolId, classId);
		}
		boolean staff = this.access.staffOf(user, scope, schoolId, classId);
		boolean canComment = staff || (this.access.people().isActiveStudent(schoolId, user.userId())
				? settings.studentsComment(schoolId)
				: settings.guardiansComment(schoolId) && !this.access.communicatingChildren(user).isEmpty());
		List<MediaKind> kinds = studentContributor ? List.of(MediaKind.IMAGE) : new ArrayList<>(settings.parentMedia(schoolId));
		return new FeedCapabilities(canPost, manager, moderate, !contributionStudents.isEmpty(),
				studentContributor || settings.parentApprovalRequired(schoolId), settings.commentsDefault(schoolId),
				canComment, kinds, settings.pinLimit(schoolId), settings.translation(schoolId),
				new ArrayList<>(contributionStudents), timeZoneOf(schoolId, campusId));
	}

	/** The time zone scheduled posts are read in: the campus's own when it has one, else the school's (UTC when unknown). */
	private String timeZoneOf(UUID schoolId, UUID campusId) {
		String name = this.jdbc.sql("""
				select coalesce(c.timezone, s.timezone) from schools s left join campuses c on c.id = :campus and c.school_id = s.id
				where s.id = :school""")
			.param("school", schoolId)
			.param("campus", campusId)
			.query(String.class)
			.optional()
			.orElse("UTC");
		try {
			return ZoneId.of(name).getId();
		}
		catch (DateTimeException ex) {
			return "UTC";
		}
	}

}
