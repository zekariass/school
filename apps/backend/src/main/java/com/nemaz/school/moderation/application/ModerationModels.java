package com.nemaz.school.moderation.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request and response models of the moderation API. Schema names are unique across the whole API. */
public final class ModerationModels {

	public static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "CRITICAL");

	public static final List<String> DECISIONS = List.of("NO_ACTION", "HIDE", "REMOVE", "WARN_USER", "SUSPEND_USER", "ESCALATE");

	@Schema(name = "ModerationReportRequest", description = """
			Report something you can see. Details are optional plain text (up to 1,000 characters) and are never copied into \
			events, logs, audit rows or notifications.""")
	public record ReportRequest(@NotBlank @Schema(allowableValues = { "POST", "POST_COMMENT", "STORY", "MESSAGE", "USER",
			"PORTFOLIO_ITEM", "TUTOR_PROFILE", "COURSE", "TUTOR_REVIEW", "LIBRARY_RESOURCE", "MEDIA", "AI_OUTPUT" }) String targetType,
			@NotNull UUID targetId, @NotBlank @Schema(allowableValues = { "SPAM", "HARASSMENT", "INAPPROPRIATE",
					"SAFETY_CONCERN", "COPYRIGHT", "PRIVACY", "INACCURATE", "OTHER" }) String reasonCode,
			String details) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "ReportRequest[redacted]";
		}

	}

	@Schema(name = "ModerationReportReceipt", description = "Only that the report was received: nothing about the case, the decision or other reporters")
	public record ReportReceipt(UUID id, Instant createdAt) {
	}

	@Schema(name = "ModerationCaseSummary", description = "A case in a queue: references and enums only, never content, reporter identities or details")
	public record CaseSummary(UUID id, UUID schoolId, String queue, String targetType, UUID targetId, String priority,
			String status, UUID assignedTo, int reportCount, int reporterCount, Map<String, Integer> reasons,
			String subjectRole, String decision, boolean legalHold, Instant createdAt, Instant updatedAt, Instant resolvedAt) {
	}

	@Schema(name = "ModerationReportEntry", description = """
			One report of a case. reporterRole ('a student', 'a guardian', ...) and the reason are shown to every moderator; \
			reporterId and details are present ONLY for safeguarding leads and platform moderators (null for everybody else).""")
	public record ReportEntry(String reasonCode, String reporterRole, Instant createdAt, UUID reporterId, String details) {
	}

	@Schema(name = "ModerationActionView", description = "One row of the append-only history of a case")
	public record ActionView(UUID id, UUID actorId, String action, String level, String notes, Instant createdAt) {
	}

	@Schema(name = "ModerationCaseDetail")
	public record CaseDetail(CaseSummary summary, UUID subjectUserId, List<ReportEntry> reports, List<ActionView> actions,
			boolean reporterIdentityVisible, @Schema(description = "Only for safeguarding leads: the incident an escalation created") UUID incidentId) {
	}

	@Schema(name = "ModerationAssignRequest", description = "The assignee; omit to take the case yourself")
	public record AssignRequest(UUID assigneeId) {
	}

	@Schema(name = "ModerationDecisionRequest", description = """
			A decision with its reason (10 to 500 characters, never pasted content). level is for SUSPEND_USER: SCHOOL (default, \
			reversible, ends the person's activity in the school) or PLATFORM (a lock of the whole account, platform moderators \
			and administrators only). category and severity are for ESCALATE (defaults CONDUCT and HIGH); the reason then becomes the \
			encrypted narrative of the incident.""")
	public record DecisionRequest(@NotBlank @Schema(allowableValues = { "NO_ACTION", "HIDE", "REMOVE", "WARN_USER",
			"SUSPEND_USER", "ESCALATE" }) String decision, @NotBlank String reason,
			@Schema(allowableValues = { "SCHOOL", "PLATFORM" }) String level, String category, String severity) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "DecisionRequest[redacted]";
		}

	}

	@Schema(name = "ModerationReasonRequest", description = "A reason of 10 to 500 characters")
	public record ReasonRequest(@NotBlank String reason) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "ReasonRequest[redacted]";
		}

	}

	@Schema(name = "ModerationPriorityRequest", description = "Safeguarding leads only. CRITICAL on a SAFETY_CONCERN case creates the safeguarding incident automatically")
	public record PriorityRequest(@NotBlank @Schema(allowableValues = { "LOW", "NORMAL", "HIGH", "CRITICAL" }) String priority) {
	}

	@Schema(name = "ModerationHoldRequest")
	public record HoldRequest(@NotNull Boolean held) {
	}

	@Schema(name = "ModerationHoldView")
	public record HoldView(UUID id, boolean legalHold) {
	}

	@Schema(name = "ModerationLockRequest", description = "A reason of 10 to 500 characters")
	public record LockRequest(@NotBlank String reason) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "LockRequest[redacted]";
		}

	}

	@Schema(name = "ModerationLockView")
	public record LockView(UUID userId, boolean locked) {
	}

	@Schema(name = "SafetyEmergencyInfo", description = """
			The per-country text the reporting screens show ('if a child is in danger contact ...'). reviewed false means the \
			wording is a placeholder the owner has not approved yet.""")
	public record EmergencyInfoView(String country, String text, boolean reviewed) {
	}

	public record CaseFilter(String status, String priority, String targetType) {
	}

}
