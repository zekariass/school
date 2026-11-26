package com.nemaz.school.portfolio.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.portfolio.application.PortfolioCommentService;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioCommentRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioCommentView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioHideRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Encouraging comments on approved, guardian-visible items. Only the student, their own guardians and the item's reviewers read or
 * write them, whatever the item's audience (403 {@code portfolio.comments_not_allowed} for classmates and other people who see the item).
 */
@RestController
@Tag(name = "portfolio")
public class PortfolioCommentController {

	private static final String BASE = "/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments";

	private final PortfolioCommentService comments;

	PortfolioCommentController(PortfolioCommentService comments) {
		this.comments = comments;
	}

	@PostMapping(BASE)
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createPortfolioComment",
			summary = "Writes a plain-text comment (1 to 500 characters, 30 an hour) on an APPROVED item that is guardian-visible: the student, their own guardians (while portfolio.comments.guardians_enabled; ACTIVE, unrestricted link with academics, read live) and the item's reviewers. 409 portfolio.comments_not_available, 403 portfolio.comments_not_allowed / portfolio.comments_disabled")
	public PortfolioCommentView create(@PathVariable UUID schoolId, @PathVariable UUID itemId, @Valid @RequestBody PortfolioCommentRequest request) {
		return this.comments.create(CurrentUser.require(), schoolId, itemId, request.body());
	}

	@GetMapping(BASE)
	@Operation(operationId = "listPortfolioComments",
			summary = "The item's comments, oldest first, keyset paged: for the student, their own guardians and the reviewers. Guardians and students appear by role label only (authorRole, mine); a hidden comment is a tombstone without text except for its author and the reviewers")
	public CursorPage<PortfolioCommentView> list(@PathVariable UUID schoolId, @PathVariable UUID itemId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.comments.list(CurrentUser.require(), schoolId, itemId, cursor, limit);
	}

	@PostMapping(BASE + "/{commentId}/hide")
	@Operation(operationId = "hidePortfolioComment",
			summary = "Hides a comment (the item's reviewers) with a reason of 1 to 200 characters: audited, the comment stays as a tombstone. Idempotent")
	public PortfolioCommentView hide(@PathVariable UUID schoolId, @PathVariable UUID itemId, @PathVariable UUID commentId,
			@Valid @RequestBody PortfolioHideRequest request) {
		return this.comments.hide(CurrentUser.require(), schoolId, itemId, commentId, request.reason());
	}

	@DeleteMapping(BASE + "/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deletePortfolioComment", summary = "Deletes the caller's own comment (the text is erased). 403 portfolio.comment_delete_not_allowed for somebody else's")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID itemId, @PathVariable UUID commentId) {
		this.comments.delete(CurrentUser.require(), schoolId, itemId, commentId);
	}

}
