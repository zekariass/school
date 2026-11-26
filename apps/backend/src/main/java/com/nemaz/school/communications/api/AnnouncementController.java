package com.nemaz.school.communications.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementCapabilities;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementCard;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementPreview;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementRequest;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementRevisionView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementScheduleRequest;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementUpdateRequest;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementWithdrawRequest;
import com.nemaz.school.communications.application.AnnouncementModels.InboxFilter;
import com.nemaz.school.communications.application.AnnouncementModels.InboxSort;
import com.nemaz.school.communications.application.AnnouncementQueries;
import com.nemaz.school.communications.application.AnnouncementReceipts;
import com.nemaz.school.communications.application.AnnouncementService;
import com.nemaz.school.communications.application.AnnouncementViews;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Announcements of one school: compose, edit, publish, schedule, withdraw, and read. The text is plain text (see
 * {@code AnnouncementView.body}). School-scoped: 404 {@code school.not_found} for callers who cannot see the school, 403
 * {@code announcement.feature_disabled} when the school switched the feature off, 404 {@code announcement.not_found} for
 * announcements the caller may not read.
 */
@RestController
@Tag(name = "announcements")
public class AnnouncementController {

	private static final String BASE = "/api/v1/schools/{schoolId}/announcements";

	private final AnnouncementService service;

	private final AnnouncementQueries queries;

	private final AnnouncementViews views;

	private final AnnouncementReceipts receipts;

	AnnouncementController(AnnouncementService service, AnnouncementQueries queries, AnnouncementViews views,
			AnnouncementReceipts receipts) {
		this.service = service;
		this.queries = queries;
		this.views = views;
		this.receipts = receipts;
	}

	@PostMapping(BASE)
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createAnnouncement",
			summary = "Composes an announcement for an explicit audience (SCHOOL, CAMPUS, GRADE_LEVEL, CLASS or chosen USERs, each with the roles GUARDIAN, STUDENT or STAFF) as a draft, published at once (mode PUBLISH) or scheduled (mode SCHEDULE with scheduledFor). Needs school.announcements.manage for school, campus, grade and staff audiences; a lead or co-teacher (setting announcements.teachers_can_announce) or class manager may announce to the guardians and students of their own classes; URGENT and SAFETY need school.announcements.urgent. Plain text only. Recipients are resolved at publish. 400 announcement.* validation codes, 403 announcement.audience_not_allowed / announcement.urgent_not_allowed, 404 class.not_found, 422 announcement.no_recipients, 429 announcement.rate_limited")
	public AnnouncementView create(@PathVariable UUID schoolId, @Valid @RequestBody AnnouncementRequest request) {
		return this.service.create(CurrentUser.require(), schoolId, request);
	}

	@GetMapping(BASE + "/capabilities")
	@Operation(operationId = "getAnnouncementCapabilities",
			summary = "What the caller may compose in the school (school-wide or only for given classes), whether they may send urgent announcements, and which optional channels the school offers. 404 for callers who cannot see the school")
	public AnnouncementCapabilities capabilities(@PathVariable UUID schoolId) {
		return this.queries.capabilities(CurrentUser.require(), schoolId);
	}

	@GetMapping(BASE + "/authored")
	@Operation(operationId = "listAuthoredAnnouncements",
			summary = "The announcements the caller wrote (all of the school for school.announcements.manage unless mine=true) in every status, newest first, keyset paged")
	public CursorPage<AnnouncementCard> authored(@PathVariable UUID schoolId,
			@RequestParam(required = false) AnnouncementStatus status,
			@RequestParam(required = false, defaultValue = "false") boolean mine, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.authored(CurrentUser.require(), schoolId, status, mine, cursor, limit);
	}

	@GetMapping(BASE)
	@Operation(operationId = "listSchoolAnnouncements",
			summary = "The published and withdrawn announcements the caller may read in the school: the ones delivered to them, their own, every one for school.announcements.manage, and those whose audience rule matches them now (a guardian linked after publication, within 180 days). Server-filtered, keyset paged, newest first (sort=UNREAD_FIRST puts unread first)")
	public CursorPage<AnnouncementCard> list(@PathVariable UUID schoolId, @RequestParam(required = false) InboxFilter filter,
			@RequestParam(required = false) InboxSort sort, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.schoolList(CurrentUser.require(), schoolId, filter, sort, cursor, limit);
	}

	@GetMapping(BASE + "/{announcementId}")
	@Operation(operationId = "getAnnouncement",
			summary = "One announcement. Readers get PLAIN_TEXT title and body (a withdrawn announcement shows title, who withdrew it when and the public note, not the body); the author and managers also get audiences, channels, recipient count and what they may do. 404 announcement.not_found when the caller may not read it")
	public AnnouncementView get(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.views.view(CurrentUser.require(), schoolId, announcementId);
	}

	@PatchMapping(BASE + "/{announcementId}")
	@Operation(operationId = "updateAnnouncement",
			summary = "Edits an announcement (author or school.announcements.manage). Drafts and scheduled ones change in place. After publishing only title, body, translations and language can change: the previous version is kept as a revision and material (required, 400 announcement.material_required) says whether recipients are told. 409 announcement.locked_after_publish for audience, type, channel or acknowledgement changes, 409 announcement.withdrawn")
	public AnnouncementView update(@PathVariable UUID schoolId, @PathVariable UUID announcementId,
			@Valid @RequestBody AnnouncementUpdateRequest request) {
		return this.service.update(CurrentUser.require(), schoolId, announcementId, request);
	}

	@DeleteMapping(BASE + "/{announcementId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteAnnouncementDraft",
			summary = "Deletes a draft or scheduled announcement (author or manager). A published one is withdrawn instead (409 announcement.invalid_state)")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		this.service.delete(CurrentUser.require(), schoolId, announcementId);
	}

	@GetMapping(BASE + "/{announcementId}/preview")
	@Operation(operationId = "previewAnnouncementAudience",
			summary = "How many people (guardians, students, staff) a draft or scheduled announcement would reach if published now. Counts only")
	public AnnouncementPreview preview(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.service.preview(CurrentUser.require(), schoolId, announcementId);
	}

	@PostMapping(BASE + "/{announcementId}/publish")
	@Operation(operationId = "publishAnnouncement",
			summary = "Publishes now: re-checks the author's rights, resolves the recipients into a snapshot and delivers through notifications (and email/SMS when chosen and allowed) in chunks after commit. Idempotent for an already published announcement. 409 announcement.withdrawn, 422 announcement.no_recipients, 429 announcement.rate_limited (30 per user per hour)")
	public AnnouncementView publish(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.service.publish(CurrentUser.require(), schoolId, announcementId);
	}

	@PostMapping(BASE + "/{announcementId}/schedule")
	@Operation(operationId = "scheduleAnnouncement",
			summary = "Schedules publication at scheduledFor (in the future, at most 180 days ahead). The scheduler publishes it as its author; if the author may no longer send it, it returns to DRAFT with scheduleErrorCode and the author is notified")
	public AnnouncementView schedule(@PathVariable UUID schoolId, @PathVariable UUID announcementId,
			@Valid @RequestBody AnnouncementScheduleRequest request) {
		return this.service.schedule(CurrentUser.require(), schoolId, announcementId, request.scheduledFor());
	}

	@PostMapping(BASE + "/{announcementId}/unschedule")
	@Operation(operationId = "unscheduleAnnouncement", summary = "Takes a scheduled announcement back to DRAFT")
	public AnnouncementView unschedule(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.service.unschedule(CurrentUser.require(), schoolId, announcementId);
	}

	@PostMapping(BASE + "/{announcementId}/withdraw")
	@Operation(operationId = "withdrawAnnouncement",
			summary = "Withdraws a published announcement (author or manager): recipients see it as withdrawn with the optional public reason, never erased; held-back pushes and pending email/SMS are cancelled; the recipients are told. Audited. Idempotent")
	public AnnouncementView withdraw(@PathVariable UUID schoolId, @PathVariable UUID announcementId,
			@Valid @RequestBody(required = false) AnnouncementWithdrawRequest request) {
		return this.service.withdraw(CurrentUser.require(), schoolId, announcementId,
				(request != null) ? request.reason() : null);
	}

	@GetMapping(BASE + "/{announcementId}/revisions")
	@Operation(operationId = "listAnnouncementRevisions",
			summary = "The previous versions of a published announcement, newest first, with who replaced them and whether the edit was material (author and managers)")
	public List<AnnouncementRevisionView> revisions(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.queries.revisions(CurrentUser.require(), schoolId, announcementId);
	}

	@PostMapping(BASE + "/{announcementId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "markAnnouncementRead",
			summary = "Records that the caller opened the announcement (the first time stays). Separate from delivery and acknowledgement; idempotent")
	public void read(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		this.receipts.markRead(CurrentUser.require(), schoolId, announcementId);
	}

	@PostMapping(BASE + "/{announcementId}/acknowledge")
	@Operation(operationId = "acknowledgeAnnouncement",
			summary = "Confirms the caller has read an announcement that asks for it (also marks it read, records the revision). Idempotent. 409 announcement.ack_not_required, 409 announcement.withdrawn")
	public AnnouncementView acknowledge(@PathVariable UUID schoolId, @PathVariable UUID announcementId) {
		return this.receipts.acknowledge(CurrentUser.require(), schoolId, announcementId);
	}

}
