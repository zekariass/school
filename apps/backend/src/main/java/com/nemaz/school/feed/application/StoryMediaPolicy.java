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
 * Who may upload and read media of purpose STORY. Only staff upload (a class's posters, school feed managers). Reading
 * follows the story: it must be PUBLISHED, <b>not past its expiry at the time of the request</b> (so a late expiry job
 * cannot leak it), visible to the reader by the feed predicate, and not withheld by a tagged student's consent. The
 * author and the moderators of the story can still read it after expiry (any state except deleted) until the expiry job
 * deletes the file seven days later.
 */
/*
 * The collaborators are injected lazily: the media module collects every policy while it is being built, and the people
 * module (behind FeedAccess) needs the media module.
 */
@Component
@Transactional(readOnly = true)
class StoryMediaPolicy implements MediaPurposePolicy {

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final ConsentWithholding consent;

	StoryMediaPolicy(JdbcClient jdbc, @Lazy FeedAccess access, @Lazy FeedVisibility visibility,
			@Lazy ConsentWithholding consent) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.consent = consent;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.STORY;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		return switch (context.type()) {
			case CLASS -> {
				ClassRef ref = this.access.classes().find(context.id()).orElse(null);
				yield ref != null && !ref.archived() && this.access.featureEnabled(ref.schoolId())
						&& this.access.classes().canView(user, ref.id()) && this.access.canPostToClass(user, ref);
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
		FeedRows.StoryRow story = storyOf(media.id());
		if (story == null || !this.access.featureEnabled(story.schoolId())) {
			return false;
		}
		if (this.access.moderates(user, story.scope(), story.schoolId(), story.classId())) {
			return true;
		}
		return this.visibility.canSeeStory(user, story.id()) && !this.consent.storyWithheld(story.id());
	}

	@Override
	public boolean canManage(CurrentUser user, MediaInfo media) {
		if (media.ownerId().equals(user.userId())) {
			return true;
		}
		FeedRows.StoryRow story = storyOf(media.id());
		return story != null && this.access.moderates(user, story.scope(), story.schoolId(), story.classId());
	}

	private FeedRows.StoryRow storyOf(UUID mediaId) {
		return this.jdbc.sql("select " + FeedRows.STORY_COLUMNS + " from stories s where s.media_id = :m and s.deleted_at is null")
			.param("m", mediaId)
			.query(FeedRows::story)
			.optional()
			.orElse(null);
	}

}
