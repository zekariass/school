package com.nemaz.school.messaging.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.messaging.application.MessagingModels.ReportView;
import com.nemaz.school.messaging.application.MessagingModels.ReviewConversationView;
import com.nemaz.school.messaging.application.MessagingModels.ReviewedPageView;
import com.nemaz.school.messaging.application.MessagingModels.StudentBlockView;
import com.nemaz.school.messaging.application.ReviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audited safeguarding review (rule book R5b, R13): holders of {@code school.messaging.review} read admin-visible and reported
 * conversations only through here. 404 for callers who cannot see the school, 403 for members who lack the permission. Not
 * gated by the {@code messaging} feature flag.
 */
@RestController
@Tag(name = "messaging-review")
public class MessagingReviewController {

	private static final String BASE = "/api/v1/schools/{schoolId}/messaging/review";

	private final ReviewService review;

	MessagingReviewController(ReviewService review) {
		this.review = review;
	}

	@GetMapping(BASE + "/conversations")
	@Operation(operationId = "listReviewableConversations",
			summary = "Conversations a reviewer may read: adult-student DIRECT conversations that no eligible guardian observes (adminVisible) and any conversation with an open report. Metadata and participants only, no text. Audited (messaging.review.list)")
	public CursorPage<ReviewConversationView> conversations(@PathVariable UUID schoolId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.review.conversations(CurrentUser.require(), schoolId, cursor, limit);
	}

	@PostMapping(BASE + "/conversations/{conversationId}/read")
	@Operation(operationId = "reviewConversation",
			summary = "Reads a reviewable conversation, newest first, including deleted originals, hidden messages and earlier versions of edited ones. A reason of 10-300 characters is mandatory (400 messaging.review_reason_required), every call writes the audit entry messaging.review.read (reviewer, conversation, reason, count; never text), 30 an hour per reviewer (429 messaging.review_rate_limited). 403 messaging.not_reviewable for conversations that are neither admin-visible nor reported")
	public ReviewedPageView read(@PathVariable UUID schoolId, @PathVariable UUID conversationId,
			@Valid @RequestBody ReviewReadRequest request) {
		return this.review.read(CurrentUser.require(), schoolId, conversationId, request.reason(), request.cursor(), request.limit());
	}

	@GetMapping(BASE + "/reports")
	@Operation(operationId = "listMessageReports", summary = "Message reports of the school (ids and reason codes only), newest first; filter by status OPEN or RESOLVED")
	public List<ReportView> reports(@PathVariable UUID schoolId, @RequestParam(required = false) String status) {
		return this.review.reports(CurrentUser.require(), schoolId, status);
	}

	@GetMapping(BASE + "/blocks")
	@Operation(operationId = "listStudentBlocksOfAdults", summary = "Blocks of adults by students of the school (ids only, no text), newest first")
	public List<StudentBlockView> blocks(@PathVariable UUID schoolId) {
		return this.review.studentBlocks(CurrentUser.require(), schoolId);
	}

	public record ReviewReadRequest(
			@NotNull(message = "validation.required") @Size(min = 10, max = 300, message = "validation.tooLong") @Schema(description = "Why the reviewer reads this conversation (10-300 characters); stored in the audit log, so never paste message content into it") String reason,
			String cursor, Integer limit) {
	}

}
