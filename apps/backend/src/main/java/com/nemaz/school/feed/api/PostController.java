package com.nemaz.school.feed.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.feed.application.FeedModels.FeedMediaRef;
import com.nemaz.school.feed.application.FeedModels.PostType;
import com.nemaz.school.feed.application.FeedModels.PostView;
import com.nemaz.school.feed.application.FeedModels.PublishMode;
import com.nemaz.school.feed.application.PostService;
import com.nemaz.school.feed.application.PostService.PostCommand;
import com.nemaz.school.feed.application.PostService.UpdateCommand;
import com.nemaz.school.feed.application.FeedQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Posts of a class or of the school. Media is uploaded first (purpose POST, context CLASS or SCHOOL, through
 * {@code /api/v1/media/uploads}), then attached here by id. Publishing and scheduling run the consent gate: 422
 * {@code feed.media_consent_missing} (with {@code students}) or 422 {@code feed.media_confirmation_required}.
 */
@RestController
@Tag(name = "feed")
public class PostController {

	private final PostService posts;

	private final FeedQueries queries;

	PostController(PostService posts, FeedQueries queries) {
		this.posts = posts;
		this.queries = queries;
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/posts")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createClassPost",
			summary = "Create a class post (draft, publish now or schedule). ACTIVE teachers of the class (assistants while feed.assistants_can_post) and class managers. Media uploaded as POST media of the class; tagged students must be ACTIVE in the class. With media: every tagged student needs MEDIA_PUBLICATION consent (else 422 feed.media_consent_missing); with no tags confirmNoUnconsentedStudents must be true (else 422 feed.media_confirmation_required)")
	public PostView createClassPost(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody PostCreateRequest r) {
		return this.posts.createClassPost(CurrentUser.require(), schoolId, classId, r.toCommand());
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createSchoolPost",
			summary = "Create a school-wide post (school.feed.manage). Media uploaded as POST media of the school; tagged students must be ACTIVE in the school. Same consent gate as class posts")
	public PostView createSchoolPost(@PathVariable UUID schoolId, @Valid @RequestBody PostCreateRequest r) {
		return this.posts.createSchoolPost(CurrentUser.require(), schoolId, r.toCommand());
	}

	@GetMapping("/api/v1/schools/{schoolId}/posts/{postId}")
	@Operation(operationId = "getPost",
			summary = "One post. Published posts to those who may see them; any status to its author and the moderators (LEAD teacher, class managers, school feed managers). 404 for everybody else")
	public PostView get(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		return this.queries.post(CurrentUser.require(), schoolId, postId);
	}

	@PatchMapping("/api/v1/schools/{schoolId}/posts/{postId}")
	@Operation(operationId = "updatePost",
			summary = "Edit your own post (null fields stay; media and studentIds replace the whole list; an empty body clears the text). Sets editedAt. Editing the photos or tags of a published post runs the consent gate again; a contribution can be edited only while pending")
	public PostView update(@PathVariable UUID schoolId, @PathVariable UUID postId, @Valid @RequestBody PostUpdateRequest r) {
		return this.posts.update(CurrentUser.require(), schoolId, postId, r.toCommand());
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/posts/{postId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deletePost",
			summary = "Delete a post (soft delete; its photos are deleted). The author, or a moderator (audited as moderation)")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		this.posts.delete(CurrentUser.require(), schoolId, postId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/publish")
	@Operation(operationId = "publishPost",
			summary = "Publish a draft, scheduled or archived post now (author or moderator). Consent gate: 422 feed.media_consent_missing / feed.media_confirmation_required")
	public PostView publish(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody(required = false) PostConfirmRequest r) {
		return this.posts.publish(CurrentUser.require(), schoolId, postId, r != null && Boolean.TRUE.equals(r.confirmNoUnconsentedStudents()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/schedule")
	@Operation(operationId = "schedulePost",
			summary = "Schedule a draft (or change the time of a scheduled post), at most 90 days ahead; the consent gate runs now and again when the scheduler publishes (a blocked post returns to DRAFT with publishErrorCode and its author is notified)")
	public PostView schedule(@PathVariable UUID schoolId, @PathVariable UUID postId, @Valid @RequestBody PostScheduleRequest r) {
		return this.posts.schedule(CurrentUser.require(), schoolId, postId, r.scheduledFor(),
				Boolean.TRUE.equals(r.confirmNoUnconsentedStudents()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/unschedule")
	@Operation(operationId = "unschedulePost", summary = "Cancel the schedule: the post becomes a draft again (author)")
	public PostView unschedule(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		return this.posts.unschedule(CurrentUser.require(), schoolId, postId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/archive")
	@Operation(operationId = "archivePost",
			summary = "Take a published post down (ARCHIVED); the author or a moderator. It can be published again")
	public PostView archive(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		return this.posts.archive(CurrentUser.require(), schoolId, postId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/hide")
	@Operation(operationId = "hidePost",
			summary = "A moderator hides a published post (audited); the optional reason is shown to its author as rejectionReason")
	public PostView hide(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody(required = false) PostHideRequest r) {
		return this.posts.hide(CurrentUser.require(), schoolId, postId, (r != null) ? r.reason() : null);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/unhide")
	@Operation(operationId = "unhidePost", summary = "A moderator shows a hidden post again (consent gate runs)")
	public PostView unhide(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody(required = false) PostConfirmRequest r) {
		return this.posts.unhide(CurrentUser.require(), schoolId, postId, r != null && Boolean.TRUE.equals(r.confirmNoUnconsentedStudents()));
	}

	@PutMapping("/api/v1/schools/{schoolId}/posts/{postId}/pin")
	@Operation(operationId = "pinPost",
			summary = "Pin a published post (its teacher author or a moderator). At most feed.pin_limit (default 3) pinned posts per class or for the school feed: 409 feed.pin_limit_reached")
	public PostView pin(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		return this.posts.pin(CurrentUser.require(), schoolId, postId, true);
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/posts/{postId}/pin")
	@Operation(operationId = "unpinPost", summary = "Unpin a post (idempotent)")
	public PostView unpin(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		return this.posts.pin(CurrentUser.require(), schoolId, postId, false);
	}

	// --- requests --------------------------------------------------------------------------

	@Schema(description = "A post. mode defaults to PUBLISH; DRAFT keeps it private to you; SCHEDULE needs scheduledFor")
	public record PostCreateRequest(@Size(max = 5000, message = "validation.tooLong") String body,
			@Size(max = 8, message = "validation.tooLong") String bodyLanguageCode, PostType postType,
			@Size(max = 10, message = "validation.tooMany") List<@Valid @NotNull FeedMediaRef> media,
			@Size(max = 50, message = "validation.tooMany") List<@NotNull UUID> studentIds, Boolean commentsEnabled,
			PublishMode mode, Instant scheduledFor,
			@Schema(description = "Required for photos when no student is tagged: you confirm that no student without media-publication consent appears in them. Audited") Boolean confirmNoUnconsentedStudents) {

		PostCommand toCommand() {
			return new PostCommand(this.body, this.bodyLanguageCode, this.postType, this.media, this.studentIds,
					this.commentsEnabled, this.mode, this.scheduledFor, Boolean.TRUE.equals(this.confirmNoUnconsentedStudents));
		}

		@Override
		public String toString() {
			return "PostCreateRequest[redacted]";
		}

	}

	public record PostUpdateRequest(@Size(max = 5000, message = "validation.tooLong") String body,
			@Size(max = 8, message = "validation.tooLong") String bodyLanguageCode, PostType postType,
			@Size(max = 10, message = "validation.tooMany") List<@Valid @NotNull FeedMediaRef> media,
			@Size(max = 50, message = "validation.tooMany") List<@NotNull UUID> studentIds, Boolean commentsEnabled,
			Boolean confirmNoUnconsentedStudents) {

		UpdateCommand toCommand() {
			return new UpdateCommand(this.body, this.bodyLanguageCode, this.postType, this.media, this.studentIds,
					this.commentsEnabled, Boolean.TRUE.equals(this.confirmNoUnconsentedStudents));
		}

		@Override
		public String toString() {
			return "PostUpdateRequest[redacted]";
		}

	}

	public record PostConfirmRequest(Boolean confirmNoUnconsentedStudents) {
	}

	public record PostScheduleRequest(@NotNull(message = "validation.required") Instant scheduledFor,
			Boolean confirmNoUnconsentedStudents) {
	}

	public record PostHideRequest(@Size(max = 500, message = "validation.tooLong") String reason) {
	}

}
