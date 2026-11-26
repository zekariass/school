package com.nemaz.school.feed.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.feed.application.ContributionService;
import com.nemaz.school.feed.application.ContributionService.ApproveCommand;
import com.nemaz.school.feed.application.ContributionService.ContributionCommand;
import com.nemaz.school.feed.application.FeedModels.FeedMediaRef;
import com.nemaz.school.feed.application.FeedModels.PostView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contributions of guardians and students to a class feed and the approval queue of the teachers (spec §18). The
 * response is a post with {@code contribution: true}; the contributor follows its status in
 * {@code GET /schools/{schoolId}/my-posts}.
 */
@RestController
@Tag(name = "feed")
public class ContributionController {

	private final ContributionService contributions;

	ContributionController(ContributionService contributions) {
		this.contributions = contributions;
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/contributions")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createContribution",
			summary = "A guardian (about a child ACTIVE in the class; link with can_view_academics and can_message_staff) or a student of the class contributes text and photos. Needs feed.parent_contributions.enabled or feed.student_contributions.enabled (403 feed.contributions_disabled). Waits for approval (PENDING_APPROVAL) unless the school switched approval off for guardians (then a photo must tag the contributor's own child and passes the consent gate); student contributions are always approved first. A guardian tags only their own children, a student nobody")
	public PostView contribute(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody ContributionCreateRequest r) {
		return this.contributions.contribute(CurrentUser.require(), schoolId, classId,
				new ContributionCommand(r.body(), r.bodyLanguageCode(), r.media(), r.studentIds()));
	}

	@GetMapping("/api/v1/schools/{schoolId}/contributions/pending")
	@Operation(operationId = "listPendingContributions",
			summary = "The approval queue, oldest first: contributions waiting in the classes the caller moderates (LEAD teacher, class managers, school feed managers). With classId only that class; 403 for everybody else who sees the school")
	public CursorPage<PostView> pending(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.contributions.queue(CurrentUser.require(), schoolId, classId, cursor, limit);
	}

	@PostMapping("/api/v1/schools/{schoolId}/contributions/{postId}/approve")
	@Operation(operationId = "approveContribution",
			summary = "Approve and publish a pending contribution (moderators). Runs the consent gate: 422 feed.media_consent_missing, or 422 feed.media_confirmation_required when no student is tagged (confirmNoUnconsentedStudents is the approver's statement). studentIds, when given, replaces the tags. Audited; the contributor is notified. 409 feed.not_pending when already decided")
	public PostView approve(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody(required = false) ContributionApproveRequest r) {
		return this.contributions.approve(CurrentUser.require(), schoolId, postId,
				(r != null) ? new ApproveCommand(Boolean.TRUE.equals(r.confirmNoUnconsentedStudents()), r.studentIds())
						: new ApproveCommand(false, null));
	}

	@PostMapping("/api/v1/schools/{schoolId}/contributions/{postId}/reject")
	@Operation(operationId = "rejectContribution",
			summary = "Reject a pending contribution with a reason the contributor sees (moderators). The photos are deleted. Audited; the contributor is notified")
	public PostView reject(@PathVariable UUID schoolId, @PathVariable UUID postId,
			@Valid @RequestBody ContributionRejectRequest r) {
		return this.contributions.reject(CurrentUser.require(), schoolId, postId, r.reason());
	}

	public record ContributionCreateRequest(@Size(max = 5000, message = "validation.tooLong") String body,
			@Size(max = 8, message = "validation.tooLong") String bodyLanguageCode,
			@Size(max = 10, message = "validation.tooMany") List<@Valid @NotNull FeedMediaRef> media,
			@Size(max = 10, message = "validation.tooMany") List<@NotNull UUID> studentIds) {

		@Override
		public String toString() {
			return "ContributionCreateRequest[redacted]";
		}

	}

	public record ContributionApproveRequest(Boolean confirmNoUnconsentedStudents,
			@Size(max = 50, message = "validation.tooMany") List<@NotNull UUID> studentIds) {
	}

	public record ContributionRejectRequest(
			@NotBlank(message = "validation.required") @Size(max = 500, message = "validation.tooLong") String reason) {
	}

}
