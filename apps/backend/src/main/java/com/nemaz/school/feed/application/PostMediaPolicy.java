package com.nemaz.school.feed.application;

import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may upload and read media of purpose POST (R-30: there is no media ACL; reads derive from the owning post).
 * <ul>
 * <li><b>Create</b> (an upload for a class or the school, before the post exists): a poster of the class (teachers,
 * class managers), a contributor of the class (a guardian of a child in it, a student of it, when the school accepts
 * contributions), or for the school context a school feed manager. The class must be viewable by the user.</li>
 * <li><b>Read</b> (signed URLs): the media must belong to a post (an upload not yet attached is readable by its uploader
 * only), the post must not be deleted and the feed must be on for the school; then either the user is a moderator of the
 * post, or the post is PUBLISHED, visible to the user (the feed predicate) and none of its tagged students lacks media
 * consent right now. The author reads through the uploader path of the media module.</li>
 * <li><b>Manage</b> (view in any state, delete): the uploader, and the post's moderators once the media is attached; a
 * teacher of the same class cannot touch another teacher's drafts or a contributor's pending upload.</li>
 * </ul>
 */
/*
 * The collaborators are injected lazily: the media module collects every policy while it is being built, and the people
 * module (behind FeedAccess) needs the media module.
 */
@Component
@Transactional(readOnly = true)
class PostMediaPolicy implements MediaPurposePolicy {

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final ConsentWithholding consent;

	PostMediaPolicy(JdbcClient jdbc, @Lazy FeedAccess access, @Lazy FeedVisibility visibility,
			@Lazy ConsentWithholding consent) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.consent = consent;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.POST;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		return switch (context.type()) {
			case CLASS -> {
				ClassRef ref = this.access.classes().find(context.id()).orElse(null);
				yield ref != null && !ref.archived() && this.access.featureEnabled(ref.schoolId())
						&& this.access.classes().canView(user, ref.id())
						&& (this.access.canPostToClass(user, ref)
								|| !this.access.contributionStudents(user, ref.schoolId(), ref.id()).isEmpty());
			}
			case SCHOOL -> this.access.featureEnabled(context.id()) && this.access.feedManager(user, context.id());
			default -> false;
		};
	}

	@Override
	public UUID schoolOf(MediaContext context) {
		return switch (context.type()) {
			case CLASS -> this.access.classes().find(context.id()).map(ClassRef::schoolId).orElse(null);
			case SCHOOL -> context.id();
			default -> null;
		};
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		FeedRows.PostRow post = postOf(media.id());
		if (post == null || !this.access.featureEnabled(post.schoolId())) {
			return false;
		}
		if (this.access.moderates(user, post)) {
			return true;
		}
		if (this.access.moderationReader(user, post.schoolId())
				&& this.jdbc.sql("select moderation_case_id is not null from posts where id = :id").param("id", post.id()).query(Boolean.class).single()) {
			return true; // content held by a moderation case stays readable by the school's moderators and leads
		}
		return post.published() && this.visibility.canSeePost(user, post.id()) && !this.consent.postWithheld(post.id());
	}

	@Override
	public boolean canManage(CurrentUser user, MediaInfo media) {
		if (media.ownerId().equals(user.userId())) {
			return true;
		}
		FeedRows.PostRow post = postOf(media.id());
		return post != null && this.access.moderates(user, post);
	}

	private FeedRows.PostRow postOf(UUID mediaId) {
		return this.jdbc.sql("select " + FeedRows.POST_COLUMNS
				+ " from post_media pm join posts p on p.id = pm.post_id where pm.media_id = :m and p.deleted_at is null")
			.param("m", mediaId)
			.query(FeedRows::post)
			.optional()
			.orElse(null);
	}

}
