package com.nemaz.school.portfolio.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.portfolio.application.PortfolioItemService;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioHistoryEntry;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemUpdateRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioNoteRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioQuickCaptureRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioReviewRequest;
import com.nemaz.school.portfolio.application.PortfolioQueries;
import com.nemaz.school.portfolio.application.PortfolioReviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

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
 * Portfolio items: a student's own work, a teacher's quick capture, the review workflow. School-scoped: 404
 * {@code school.not_found} for callers who cannot see the school, 403 {@code portfolio.feature_disabled} when the school switched the
 * feature off, 404 {@code portfolio.item_not_found} for items the caller may not see (exactly like unknown ids). Media is uploaded
 * first (purpose PORTFOLIO, context SCHOOL, through {@code /api/v1/media/uploads}) and attached here by id.
 */
@RestController
@Tag(name = "portfolio")
public class PortfolioItemController {

	private static final String BASE = "/api/v1/schools/{schoolId}/portfolio/items";

	private final PortfolioItemService items;

	private final PortfolioReviewService reviews;

	private final PortfolioQueries queries;

	PortfolioItemController(PortfolioItemService items, PortfolioReviewService reviews, PortfolioQueries queries) {
		this.items = items;
		this.reviews = reviews;
		this.queries = queries;
	}

	@PostMapping(BASE)
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createPortfolioItem",
			summary = "A student adds an item about their own work (TEXT, PHOTO, DRAWING, SCAN or DOCUMENT; AUDIO and VIDEO are refused with 422 portfolio.item_type_unsupported). Only an ACTIVE student of the school while portfolio.student_create.enabled (403 portfolio.student_only / portfolio.student_create_disabled). mode DRAFT (default) or SUBMIT. Files are READY uploads of purpose PORTFOLIO with context SCHOOL. Text is plain text. visibility defaults to GUARDIANS and is lowered by the school cap. 400 portfolio.* validation codes, 429 portfolio.rate_limited")
	public PortfolioItemView create(@PathVariable UUID schoolId, @Valid @RequestBody PortfolioItemRequest request) {
		return this.items.create(CurrentUser.require(), schoolId, request);
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/portfolio/quick-capture")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "quickCapturePortfolioItem",
			summary = "A teacher captures an item for a student ACTIVE in the class (ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER; assistants while portfolio.assistants_can_capture). The item is APPROVED at once, created_by is the teacher, audited. showsOtherChildren defaults to true: with files, tag the other children (each needs media consent) or send confirmNoUnconsentedStudents. 422 portfolio.media_consent_missing / portfolio.media_confirmation_required, 403 portfolio.capture_not_allowed")
	public PortfolioItemView quickCapture(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody PortfolioQuickCaptureRequest request) {
		return this.items.quickCapture(CurrentUser.require(), schoolId, classId, request);
	}

	@GetMapping(BASE + "/{itemId}")
	@Operation(operationId = "getPortfolioItem",
			summary = "One item as the caller may see it: the student in every status, reviewers in every status but DRAFT, everybody else only APPROVED items within the effective visibility (the lower of the item's and the school cap). Files are withheld (mediaWithheld) while media consent is missing. 404 portfolio.item_not_found for everybody else")
	public PortfolioItemView get(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		return this.queries.item(CurrentUser.require(), schoolId, itemId);
	}

	@PatchMapping(BASE + "/{itemId}")
	@Operation(operationId = "updatePortfolioItem",
			summary = "Edits a DRAFT or CHANGES_REQUESTED item (its student only; 409 portfolio.invalid_state otherwise). Changing the files or tagged children drops the creator's confirmation")
	public PortfolioItemView update(@PathVariable UUID schoolId, @PathVariable UUID itemId,
			@Valid @RequestBody PortfolioItemUpdateRequest request) {
		return this.items.update(CurrentUser.require(), schoolId, itemId, request);
	}

	@DeleteMapping(BASE + "/{itemId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deletePortfolioItem",
			summary = "Deletes an item (soft; its text is erased, its files discarded, the history stays): the student's own DRAFT, SUBMITTED or CHANGES_REQUESTED item, or any item by a reviewer. 403 portfolio.delete_not_allowed for the others")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		this.items.delete(CurrentUser.require(), schoolId, itemId);
	}

	@PostMapping(BASE + "/{itemId}/submit")
	@Operation(operationId = "submitPortfolioItem",
			summary = "Sends a DRAFT item, or a CHANGES_REQUESTED one again, for review (its student). The item must be complete (text for TEXT, a file for the others). Appends a SUBMITTED row to the history; the reviewing teachers are told. 409 portfolio.invalid_state")
	public PortfolioItemView submit(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		return this.items.submit(CurrentUser.require(), schoolId, itemId);
	}

	@PostMapping(BASE + "/{itemId}/review")
	@Operation(operationId = "reviewPortfolioItem",
			summary = "Decides on a SUBMITTED item (reviewers: the class's LEAD and CO_TEACHERs, its SUBJECT_TEACHER when the subject matches, class managers, holders of school.portfolios.review): APPROVE (the consent gate runs: 422 portfolio.media_consent_missing / portfolio.media_confirmation_required), REQUEST_CHANGES or REJECT (a note is required). May lower the visibility, never raise it. 403 portfolio.review_not_allowed, 409 portfolio.invalid_state")
	public PortfolioItemView review(@PathVariable UUID schoolId, @PathVariable UUID itemId, @Valid @RequestBody PortfolioReviewRequest request) {
		return this.reviews.review(CurrentUser.require(), schoolId, itemId, request);
	}

	@PostMapping(BASE + "/{itemId}/archive")
	@Operation(operationId = "archivePortfolioItem",
			summary = "Archives an APPROVED item (the student or a reviewer): hidden from everybody but the student and the reviewers")
	public PortfolioItemView archive(@PathVariable UUID schoolId, @PathVariable UUID itemId,
			@Valid @RequestBody(required = false) PortfolioNoteRequest request) {
		return this.reviews.archive(CurrentUser.require(), schoolId, itemId, (request != null) ? request.note() : null);
	}

	@PostMapping(BASE + "/{itemId}/unarchive")
	@Operation(operationId = "unarchivePortfolioItem",
			summary = "Brings an ARCHIVED item back to APPROVED (reviewers only); the consent gate runs again")
	public PortfolioItemView unarchive(@PathVariable UUID schoolId, @PathVariable UUID itemId,
			@Valid @RequestBody(required = false) PortfolioNoteRequest request) {
		return this.reviews.unarchive(CurrentUser.require(), schoolId, itemId, (request != null) ? request.note() : null);
	}

	@PutMapping(BASE + "/{itemId}/featured")
	@Operation(operationId = "featurePortfolioItem", summary = "Features an APPROVED item (reviewers); idempotent")
	public PortfolioItemView feature(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		return this.reviews.feature(CurrentUser.require(), schoolId, itemId, true);
	}

	@DeleteMapping(BASE + "/{itemId}/featured")
	@Operation(operationId = "unfeaturePortfolioItem", summary = "Removes the featured mark (reviewers); idempotent")
	public PortfolioItemView unfeature(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		return this.reviews.feature(CurrentUser.require(), schoolId, itemId, false);
	}

	@PostMapping(BASE + "/{itemId}/share-to-story")
	@Operation(operationId = "sharePortfolioItemToStory",
			summary = "Deferred (rule book P6): reviewers of an APPROVED item with effective visibility CLASS or SCHOOL get 422 portfolio.share_unsupported (409 portfolio.share_not_eligible otherwise); everybody else the usual 403 and 404. Sharing needs a media copy operation that does not exist yet")
	public void shareToStory(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		this.reviews.shareToStory(CurrentUser.require(), schoolId, itemId);
	}

	@GetMapping(BASE + "/{itemId}/history")
	@Operation(operationId = "getPortfolioItemHistory",
			summary = "The status history (append-only), oldest first, with the reviewers' notes. For the student and the reviewers only (403 portfolio.history_not_allowed for the others who see the item)")
	public List<PortfolioHistoryEntry> history(@PathVariable UUID schoolId, @PathVariable UUID itemId) {
		return this.queries.history(CurrentUser.require(), schoolId, itemId);
	}

}
