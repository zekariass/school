package com.nemaz.school.moderation.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.moderation.ReviewContent;
import com.nemaz.school.moderation.application.CaseQueries;
import com.nemaz.school.moderation.application.CaseService;
import com.nemaz.school.moderation.application.ModerationModels.AssignRequest;
import com.nemaz.school.moderation.application.ModerationModels.CaseDetail;
import com.nemaz.school.moderation.application.ModerationModels.CaseFilter;
import com.nemaz.school.moderation.application.ModerationModels.CaseSummary;
import com.nemaz.school.moderation.application.ModerationModels.DecisionRequest;
import com.nemaz.school.moderation.application.ModerationModels.HoldRequest;
import com.nemaz.school.moderation.application.ModerationModels.HoldView;
import com.nemaz.school.moderation.application.ModerationModels.LockRequest;
import com.nemaz.school.moderation.application.ModerationModels.LockView;
import com.nemaz.school.moderation.application.ModerationModels.PriorityRequest;
import com.nemaz.school.moderation.application.ModerationModels.ReasonRequest;
import com.nemaz.school.moderation.application.PlatformLockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The moderation queues and cases (docs/Safeguarding_Rules.md S2, S3). Authorization is server-side only: the school queue needs
 * {@code school.moderation.manage} at the school, the platform queue {@code platform.moderation.manage}; a case a person may not
 * work (not their queue, about themselves, their own content, their child, or reported by them) is 404, like an unknown id.
 */
@RestController
@Tag(name = "moderation")
public class CaseController {

	private final CaseQueries queries;

	private final CaseService cases;

	private final PlatformLockService locks;

	CaseController(CaseQueries queries, CaseService cases, PlatformLockService locks) {
		this.queries = queries;
		this.cases = cases;
		this.locks = locks;
	}

	@GetMapping("/api/v1/schools/{schoolId}/moderation/cases")
	@Operation(operationId = "listSchoolModerationCases", summary = """
			The school's moderation queue (school.moderation.manage): open cases, HIGH and CRITICAL first, then newest, keyset paged. \
			status is OPEN (default: open and assigned), ASSIGNED, RESOLVED, ESCALATED or ALL; priority and targetType filter. \
			References and enums only: never content, reporter identities or details. A case about a school administrator, \
			moderator or safeguarding lead is not here: it is in the platform queue.""")
	public CursorPage<CaseSummary> schoolQueue(@PathVariable UUID schoolId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String priority, @RequestParam(required = false) String targetType,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.schoolQueue(CurrentUser.require(), schoolId, new CaseFilter(status, priority, targetType), cursor, limit);
	}

	@GetMapping("/api/v1/platform/moderation/cases")
	@PreAuthorize("@authz.platform('platform.moderation.manage')")
	@Operation(operationId = "listPlatformModerationCases", summary = """
			The platform moderation queue (platform.moderation.manage): cases about platform-scope targets, about school \
			administrators, moderators and safeguarding leads, and cases a school moderator handed up.""")
	public CursorPage<CaseSummary> platformQueue(@RequestParam(required = false) String status,
			@RequestParam(required = false) String priority, @RequestParam(required = false) String targetType,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.platformQueue(CurrentUser.require(), new CaseFilter(status, priority, targetType), cursor, limit);
	}

	@GetMapping("/api/v1/moderation/cases/{caseId}")
	@Operation(operationId = "getModerationCase", summary = """
			A case: counts, reasons, the role of each reporter ('a student', 'a guardian', ...), the append-only history. Reporter ids \
			and report details are present ONLY for safeguarding leads and platform moderators; the incident an escalation created \
			only for safeguarding leads. 404 for anybody who may not work the case.""")
	public CaseDetail get(@PathVariable UUID caseId) {
		return this.queries.detail(CurrentUser.require(), caseId);
	}

	@GetMapping("/api/v1/moderation/cases/{caseId}/content")
	@Operation(operationId = "readModerationCaseContent", summary = """
			Read the reported target and its immediate context through its owner's reviewer path (a post with its comments, a comment \
			with its post, a story). Audited (reviewer, case, target: never text) and rate limited (60 an hour). For a message the answer \
			is a pointer to the audited messaging review (kind MESSAGING_REVIEW, reviewConversationId), never the text. Hidden and \
			removed content stays readable here.""")
	public ReviewContent content(@PathVariable UUID caseId) {
		return this.cases.readContent(CurrentUser.require(), caseId);
	}

	@PostMapping("/api/v1/moderation/cases/{caseId}/assign")
	@Operation(operationId = "assignModerationCase", summary = "Take the case or assign it to another moderator of the queue (who must not be conflicted). Audited; the assignee is notified")
	public CaseDetail assign(@PathVariable UUID caseId, @RequestBody(required = false) AssignRequest request) {
		return this.cases.assign(CurrentUser.require(), caseId, (request != null) ? request.assigneeId() : null);
	}

	@PostMapping("/api/v1/moderation/cases/{caseId}/decision")
	@Operation(operationId = "decideModerationCase", summary = """
			Decide an open case with a reason of 10 to 500 characters: NO_ACTION, HIDE (reversible), REMOVE, WARN_USER (a generic notice), \
			SUSPEND_USER (level SCHOOL by default; PLATFORM locks the account and needs platform.users.lock) or ESCALATE (creates a \
			safeguarding incident; the reason becomes its encrypted narrative). Hiding and removing keep the original. The reporters get one \
			generic 'reviewed' notice. Append-only history; audited without the reason.""")
	public CaseDetail decide(@PathVariable UUID caseId, @Valid @RequestBody DecisionRequest request) {
		return this.cases.decide(CurrentUser.require(), caseId, request);
	}

	@PostMapping("/api/v1/moderation/cases/{caseId}/reopen")
	@Operation(operationId = "reopenModerationCase", summary = "Open a resolved case again with a reason (a case escalated to safeguarding only by leads). Audited")
	public CaseDetail reopen(@PathVariable UUID caseId, @Valid @RequestBody ReasonRequest request) {
		return this.cases.reopen(CurrentUser.require(), caseId, request.reason());
	}

	@PostMapping("/api/v1/moderation/cases/{caseId}/restore")
	@Operation(operationId = "restoreModerationCaseContent", summary = "Undo the HIDE of this case (the content is shown again); a REMOVE is not reversible (409 moderation.removal_not_reversible). Audited")
	public CaseDetail restore(@PathVariable UUID caseId, @Valid @RequestBody ReasonRequest request) {
		return this.cases.restore(CurrentUser.require(), caseId, request.reason());
	}

	@PostMapping("/api/v1/moderation/cases/{caseId}/lift-suspension")
	@Operation(operationId = "liftModerationCaseSuspension", summary = "Lift the suspension this case decided: a school suspension, or a platform lock (needs platform.users.lock). Audited")
	public CaseDetail lift(@PathVariable UUID caseId, @Valid @RequestBody ReasonRequest request) {
		return this.cases.liftSuspension(CurrentUser.require(), caseId, request.reason());
	}

	@PostMapping("/api/v1/moderation/cases/{caseId}/refer-up")
	@Operation(operationId = "referModerationCaseUp", summary = "Hand a school case up to the platform queue (a conflict, or a matter above the school). Audited")
	public CaseDetail referUp(@PathVariable UUID caseId, @Valid @RequestBody ReasonRequest request) {
		return this.cases.referUp(CurrentUser.require(), caseId, request.reason());
	}

	@PutMapping("/api/v1/moderation/cases/{caseId}/priority")
	@Operation(operationId = "setModerationCasePriority", summary = "Set the priority (safeguarding leads only). CRITICAL on a case with a SAFETY_CONCERN report creates the safeguarding incident automatically")
	public CaseDetail priority(@PathVariable UUID caseId, @Valid @RequestBody PriorityRequest request) {
		return this.cases.setPriority(CurrentUser.require(), caseId, request.priority());
	}

	@PutMapping("/api/v1/moderation/cases/{caseId}/legal-hold")
	@Operation(operationId = "setModerationCaseLegalHold", summary = "Set or lift the legal hold of a case (a safeguarding lead of its school, or platform.moderation.manage). A hold blocks the retention purge and every erasure. Audited")
	public HoldView legalHold(@PathVariable UUID caseId, @Valid @RequestBody HoldRequest request) {
		return this.cases.setLegalHold(CurrentUser.require(), caseId, request.held());
	}

	@PostMapping("/api/v1/platform/users/{userId}/lock")
	@PreAuthorize("@authz.platform('platform.users.lock')")
	@Operation(operationId = "lockUserAccount", summary = """
			Lock an account platform-wide (platform.users.lock: platform moderators and administrators; a school moderator never). \
			Every session is revoked through the token service, so sockets and push devices close at once; nothing is deleted. \
			Reason of 10 to 500 characters, audited. Reversible with unlock.""")
	public LockView lock(@PathVariable UUID userId, @Valid @RequestBody LockRequest request) {
		return this.locks.lock(CurrentUser.require(), userId, request.reason());
	}

	@PostMapping("/api/v1/platform/users/{userId}/unlock")
	@PreAuthorize("@authz.platform('platform.users.lock')")
	@Operation(operationId = "unlockUserAccount", summary = "Unlock a locked account (platform.users.lock). The person signs in again. Reason of 10 to 500 characters, audited")
	public LockView unlock(@PathVariable UUID userId, @Valid @RequestBody LockRequest request) {
		return this.locks.unlock(CurrentUser.require(), userId, request.reason());
	}

}
