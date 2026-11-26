package com.nemaz.school.feed.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.application.FeedModels.FeedAuthor;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedMediaItem;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedModels.FeedStudentTag;
import com.nemaz.school.feed.application.FeedModels.PostRights;
import com.nemaz.school.feed.application.FeedModels.PostStatus;
import com.nemaz.school.feed.application.FeedModels.PostView;
import com.nemaz.school.feed.application.FeedModels.ReactionType;
import com.nemaz.school.feed.application.FeedRows.PostRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a post for a caller and turns rows into {@link PostView}s with the privacy rules applied in one place:
 * who may read the post at all ({@link #requireReadable}), which photos are shown (consent withholding), which students
 * and which author are named ({@link FeedViewer}), and what the caller may do with it ({@link PostRights}).
 */
@Service
@Transactional(readOnly = true)
public class PostViews {

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final FeedMedia media;

	private final ConsentWithholding consent;

	PostViews(JdbcClient jdbc, FeedAccess access, FeedVisibility visibility, FeedMedia media, ConsentWithholding consent) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.media = media;
		this.consent = consent;
	}

	// --- loading ---------------------------------------------------------------------------

	PostRow find(UUID schoolId, UUID postId) {
		return this.jdbc.sql("select " + FeedRows.POST_COLUMNS + " from posts p where p.id = :id and p.school_id = :school and p.deleted_at is null")
			.param("id", postId)
			.param("school", schoolId)
			.query(FeedRows::post)
			.optional()
			.orElseThrow(() -> ApiException.notFound("feed.post_not_found"));
	}

	/** The post row with a row lock, for state changes. */
	PostRow lock(UUID schoolId, UUID postId) {
		return this.jdbc.sql("select " + FeedRows.POST_COLUMNS
				+ " from posts p where p.id = :id and p.school_id = :school and p.deleted_at is null for update")
			.param("id", postId)
			.param("school", schoolId)
			.query(FeedRows::post)
			.optional()
			.orElseThrow(() -> ApiException.notFound("feed.post_not_found"));
	}

	/**
	 * The post if the caller may read it: published posts the caller may see (the feed predicate), and any not-deleted post to
	 * its author (while the author can still see the class or school) and to the moderators. Otherwise 404.
	 */
	PostRow requireReadable(CurrentUser user, UUID schoolId, UUID postId) {
		PostRow row = find(schoolId, postId);
		if (readable(user, row)) {
			return row;
		}
		throw ApiException.notFound("feed.post_not_found");
	}

	boolean readable(CurrentUser user, PostRow row) {
		if (row.published() && this.visibility.canSeePost(user, row.id())) {
			return true;
		}
		if (row.authorId().equals(user.userId()) && stillBelongs(user, row)) {
			return true;
		}
		return this.access.moderates(user, row);
	}

	/** An author keeps access to their own unpublished posts only while they can still see the class or school. */
	private boolean stillBelongs(CurrentUser user, PostRow row) {
		return (row.scope() == FeedScope.CLASS) ? this.access.classes().canView(user, row.classId())
				: this.visibility.canSeeSchool(user, row.schoolId());
	}

	// --- mapping ---------------------------------------------------------------------------

	public PostView one(CurrentUser user, PostRow row) {
		return map(user, List.of(row)).getFirst();
	}

	List<PostView> map(CurrentUser user, List<PostRow> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		FeedViewer viewer = new FeedViewer(user, this.access);
		List<UUID> ids = rows.stream().map(PostRow::id).toList();
		Map<UUID, List<FeedMediaItem>> mediaByPost = this.media.itemsOfPosts(ids);
		Map<UUID, List<UUID>> tagsByPost = tags(ids);
		Set<UUID> withheld = this.consent.withheldPosts(mediaByPost.keySet());
		Map<UUID, Integer> comments = commentCounts(ids);
		Map<UUID, Map<String, Integer>> reactions = reactionCounts(ids);
		Map<UUID, ReactionType> mine = myReactions(user.userId(), ids);
		List<UUID> seenFor = rows.stream().filter((r) -> mayCountSeen(viewer, r)).map(PostRow::id).toList();
		Map<UUID, Integer> seen = seenCounts(seenFor);

		Set<UUID> people = new HashSet<>();
		for (PostRow row : rows) {
			if (viewer.canSeeName(row.authorRole(), row.authorId(), row.scope(), row.schoolId(), row.classId())) {
				people.add(row.authorId());
			}
			for (UUID tag : tagsByPost.getOrDefault(row.id(), List.of())) {
				if (viewer.canSeeTag(row.scope(), row.schoolId(), row.classId(), tag)) {
					people.add(tag);
				}
			}
		}
		Map<UUID, PersonCard> cards = this.access.people().cards(people);

		List<PostView> views = new ArrayList<>();
		for (PostRow row : rows) {
			boolean author = row.authorId().equals(user.userId());
			boolean moderator = viewer.moderates(row);
			boolean privileged = author || moderator;
			List<FeedMediaItem> items = mediaByPost.getOrDefault(row.id(), List.of());
			boolean isWithheld = !items.isEmpty() && withheld.contains(row.id());
			List<FeedStudentTag> tags = new ArrayList<>();
			for (UUID tag : tagsByPost.getOrDefault(row.id(), List.of())) {
				PersonCard card = cards.get(tag);
				if (card != null && viewer.canSeeTag(row.scope(), row.schoolId(), row.classId(), tag)) {
					tags.add(new FeedStudentTag(tag, card.displayName(), card.avatar()));
				}
			}
			views.add(new PostView(row.id(), row.scope(), row.schoolId(), row.classId(), row.status(), row.postType(),
					author(viewer, row.authorRole(), row.authorId(), row.scope(), row.schoolId(), row.classId(), cards),
					row.body(), row.bodyLanguage(), row.contribution(), row.publishedAt(), row.scheduledFor(),
					row.createdAt(), row.editedAt(), row.pinned(), row.commentsEnabled(),
					(isWithheld && !privileged) ? List.of() : items, isWithheld, tags,
					comments.getOrDefault(row.id(), 0), reactions.getOrDefault(row.id(), Map.of()), mine.get(row.id()),
					seenFor.contains(row.id()) ? seen.getOrDefault(row.id(), 0) : null,
					(author || moderator) ? row.rejectionReason() : null,
					(author || moderator) ? row.publishErrorCode() : null, rights(viewer, row, author, moderator)));
		}
		return views;
	}

	static FeedAuthor author(FeedViewer viewer, FeedAuthorRole role, UUID authorId, FeedScope scope, UUID schoolId,
			UUID classId, Map<UUID, PersonCard> cards) {
		PersonCard card = cards.get(authorId);
		if (card != null && viewer.canSeeName(role, authorId, scope, schoolId, classId)) {
			return new FeedAuthor(authorId, role, card.displayName(), card.avatar());
		}
		return new FeedAuthor(null, role, null, null);
	}

	private boolean mayCountSeen(FeedViewer viewer, PostRow row) {
		return row.published() && (row.authorId().equals(viewer.id()) || viewer.staff(row.scope(), row.schoolId(), row.classId())
				|| viewer.moderates(row));
	}

	private PostRights rights(FeedViewer viewer, PostRow row, boolean author, boolean moderator) {
		boolean live = row.deletedAt() == null;
		// A contribution is editable only while it waits for approval: it must not change after a teacher approved it.
		boolean editable = author && live && (row.contribution() ? row.status() == PostStatus.PENDING_APPROVAL
				: (row.status() == PostStatus.DRAFT || row.status() == PostStatus.SCHEDULED
						|| row.status() == PostStatus.PENDING_APPROVAL || row.status() == PostStatus.PUBLISHED));
		boolean teacherAuthor = author && (row.authorRole() == FeedAuthorRole.TEACHER
				|| row.authorRole() == FeedAuthorRole.SCHOOL_ADMIN);
		boolean canPin = live && row.published() && (moderator || teacherAuthor);
		boolean canComment = row.published() && row.commentsEnabled() && viewer.mayComment(row);
		boolean canPublish = live && (author || moderator) && !row.contribution()
				&& (row.status() == PostStatus.DRAFT || row.status() == PostStatus.SCHEDULED
						|| row.status() == PostStatus.ARCHIVED);
		return new PostRights(editable, live && (author || moderator), moderator, canPin, canComment, canPublish);
	}

	// --- batch queries ---------------------------------------------------------------------

	Map<UUID, List<UUID>> tags(Collection<UUID> postIds) {
		Map<UUID, List<UUID>> tags = new HashMap<>();
		this.jdbc.sql("select post_id, student_id from post_student_tags where post_id in (:ids) order by post_id, student_id")
			.param("ids", postIds)
			.query((rs, i) -> {
				tags.computeIfAbsent(Rows.uuid(rs, "post_id"), (k) -> new ArrayList<>()).add(Rows.uuid(rs, "student_id"));
				return 0;
			})
			.list();
		return tags;
	}

	List<UUID> tagsOf(UUID postId) {
		return tags(List.of(postId)).getOrDefault(postId, List.of());
	}

	private Map<UUID, Integer> commentCounts(Collection<UUID> postIds) {
		Map<UUID, Integer> counts = new HashMap<>();
		this.jdbc.sql("select post_id, count(*) as n from post_comments where post_id in (:ids) and status = 'VISIBLE' group by post_id")
			.param("ids", postIds)
			.query((rs, i) -> {
				counts.put(Rows.uuid(rs, "post_id"), rs.getInt("n"));
				return 0;
			})
			.list();
		return counts;
	}

	private Map<UUID, Map<String, Integer>> reactionCounts(Collection<UUID> postIds) {
		Map<UUID, Map<String, Integer>> counts = new HashMap<>();
		this.jdbc.sql("select post_id, reaction, count(*) as n from post_reactions where post_id in (:ids) group by post_id, reaction order by reaction")
			.param("ids", postIds)
			.query((rs, i) -> {
				counts.computeIfAbsent(Rows.uuid(rs, "post_id"), (k) -> new LinkedHashMap<>())
					.put(rs.getString("reaction"), rs.getInt("n"));
				return 0;
			})
			.list();
		return counts;
	}

	private Map<UUID, ReactionType> myReactions(UUID userId, Collection<UUID> postIds) {
		Map<UUID, ReactionType> mine = new HashMap<>();
		this.jdbc.sql("select post_id, reaction from post_reactions where user_id = :u and post_id in (:ids)")
			.param("u", userId)
			.param("ids", postIds)
			.query((rs, i) -> {
				mine.put(Rows.uuid(rs, "post_id"), ReactionType.valueOf(rs.getString("reaction")));
				return 0;
			})
			.list();
		return mine;
	}

	private Map<UUID, Integer> seenCounts(Collection<UUID> postIds) {
		Map<UUID, Integer> counts = new HashMap<>();
		if (postIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("select pv.post_id, count(*) as n from post_views pv join posts p on p.id = pv.post_id "
				+ "where pv.post_id in (:ids) and pv.user_id <> p.author_id group by pv.post_id")
			.param("ids", postIds)
			.query((rs, i) -> {
				counts.put(Rows.uuid(rs, "post_id"), rs.getInt("n"));
				return 0;
			})
			.list();
		return counts;
	}

}
