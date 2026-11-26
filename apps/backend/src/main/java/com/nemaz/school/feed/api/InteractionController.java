package com.nemaz.school.feed.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.feed.application.FeedModels.CommentView;
import com.nemaz.school.feed.application.FeedModels.ReactionSummary;
import com.nemaz.school.feed.application.FeedModels.ReactionType;
import com.nemaz.school.feed.application.FeedModels.ReportReason;
import com.nemaz.school.feed.application.FeedModels.TranslationView;
import com.nemaz.school.feed.application.InteractionService;
import com.nemaz.school.feed.application.InteractionService.CommentCommand;
import com.nemaz.school.feed.application.TranslationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Comments, reactions, "seen by", reports and translations of published posts. */
@RestController
@Tag(name = "feed")
public class InteractionController {

	private final InteractionService interactions;

	private final TranslationService translations;

	InteractionController(InteractionService interactions, TranslationService translations) {
		this.interactions = interactions;
		this.translations = translations;
	}

	@GetMapping("/api/v1/schools/{schoolId}/posts/{postId}/comments")
	@Operation(operationId = "listPostComments",
			summary = "Comments of a post, oldest first. VISIBLE ones to everybody who sees the post; HIDDEN ones also to their author and the moderators. Authors are named only as the privacy rules allow (guardians and students otherwise appear by role label)")
	public CursorPage<CommentView> comments(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.interactions.comments(CurrentUser.require(), schoolId, postId, cursor, limit);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createPostComment",
			summary = "Comment on a published post as yourself. Needs comments switched on for the post (403 feed.comments_disabled) and your relation to allow it: teachers and managers; guardians (link with can_view_academics and can_message_staff) while feed.comments.guardians_can_comment; students while feed.comments.students_can_comment (403 feed.comment_not_allowed)")
	public CommentView comment(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody CommentCreateRequest r) {
		return this.interactions.comment(CurrentUser.require(), schoolId, postId, new CommentCommand(r.body(), r.bodyLanguageCode()));
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deletePostComment", summary = "Delete a comment: its author, or a moderator (audited)")
	public void deleteComment(@PathVariable UUID schoolId, @PathVariable UUID postId, @PathVariable UUID commentId) {
		this.interactions.deleteComment(CurrentUser.require(), schoolId, postId, commentId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}/hide")
	@Operation(operationId = "hidePostComment", summary = "A moderator hides a comment (it stays visible to its author and moderators with status HIDDEN; audited)")
	public CommentView hideComment(@PathVariable UUID schoolId, @PathVariable UUID postId, @PathVariable UUID commentId) {
		return this.interactions.hideComment(CurrentUser.require(), schoolId, postId, commentId, true);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}/unhide")
	@Operation(operationId = "unhidePostComment", summary = "A moderator shows a hidden comment again (audited)")
	public CommentView unhideComment(@PathVariable UUID schoolId, @PathVariable UUID postId, @PathVariable UUID commentId) {
		return this.interactions.hideComment(CurrentUser.require(), schoolId, postId, commentId, false);
	}

	@PutMapping("/api/v1/schools/{schoolId}/posts/{postId}/reaction")
	@Operation(operationId = "reactToPost",
			summary = "Set your one reaction to a published post you can see (replaces the previous one; repeating it changes nothing)")
	public ReactionSummary react(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody PostReactionRequest r) {
		return this.interactions.react(CurrentUser.require(), schoolId, postId, r.reaction());
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/posts/{postId}/reaction")
	@Operation(operationId = "removePostReaction", summary = "Remove your reaction (idempotent)")
	public ReactionSummary unreact(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		return this.interactions.unreact(CurrentUser.require(), schoolId, postId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/view")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "markPostSeen",
			summary = "Record that the caller saw the post (once per viewer). Only a count is ever returned (seenCount), to the author and the class's staff; names are never exposed")
	public void markSeen(@PathVariable UUID schoolId, @PathVariable UUID postId) {
		this.interactions.markSeen(CurrentUser.require(), schoolId, postId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/report")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(operationId = "reportPost",
			summary = "Report a post you can see. A stub for the moderation queue of M13: it is audited and published as a ContentReported event; at most 20 reports a day")
	public void report(@PathVariable UUID schoolId, @PathVariable UUID postId, @Valid @RequestBody PostReportRequest r) {
		this.interactions.report(CurrentUser.require(), schoolId, postId, r.reason());
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/translation")
	@Operation(operationId = "translatePost",
			summary = "See translation of a published post (decision D5): the cached machine translation, or one made through the provider adapter. 403 feed.translation_disabled unless the school switched feed.translation.enabled on (off by default), 503 feed.translation_unavailable without a provider. Only the post text leaves the platform, with tagged students' names masked. Always machineTranslated: true")
	public TranslationView translatePost(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@RequestParam(required = false) String language) {
		return this.translations.translatePost(CurrentUser.require(), schoolId, postId, language);
	}

	@PostMapping("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}/translation")
	@Operation(operationId = "translatePostComment", summary = "See translation of a visible comment (same rules as translatePost)")
	public TranslationView translateComment(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@PathVariable UUID commentId, @RequestParam(required = false) String language) {
		return this.translations.translateComment(CurrentUser.require(), schoolId, postId, commentId, language);
	}

	public record CommentCreateRequest(
			@NotBlank(message = "validation.required") @Size(max = 1000, message = "validation.tooLong") String body,
			@Size(max = 8, message = "validation.tooLong") String bodyLanguageCode) {

		@Override
		public String toString() {
			return "CommentCreateRequest[redacted]";
		}

	}

	public record PostReactionRequest(@NotNull(message = "validation.required") ReactionType reaction) {
	}

	public record PostReportRequest(@NotNull(message = "validation.required") ReportReason reason) {
	}

}
