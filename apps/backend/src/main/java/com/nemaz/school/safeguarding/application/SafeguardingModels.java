package com.nemaz.school.safeguarding.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response models of the safeguarding API. Schema names are unique across the whole API. */
public final class SafeguardingModels {

	public static final List<String> CATEGORIES = List.of("CONDUCT", "ONLINE_SAFETY", "WELLBEING", "TUTOR_CONDUCT",
			"DATA_BREACH", "OTHER");

	public static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

	public static final List<String> STATUSES = List.of("OPEN", "INVESTIGATING", "ACTION_TAKEN", "REFERRED", "CLOSED");

	/**
	 * Why a lead opens an incident: a pick-list, not free text (a free text would end up in the audit rows that platform
	 * administrators can list, and nothing about a child may be pasted there).
	 */
	@Schema(name = "SafeguardingReadReason")
	public enum ReadReason {

		TRIAGE, INVESTIGATION, FOLLOW_UP, REFERRAL, OVERSIGHT, LEGAL_REQUEST

	}

	@Schema(name = "SafeguardingConcernRequest", description = """
			Raise a concern (an ACTIVE staff member or teacher of the school). The narrative is encrypted at once and can be \
			read only by safeguarding leads. Students and guardians raise concerns through a report, never here.""")
	public record ConcernRequest(@NotBlank @Schema(allowableValues = { "CONDUCT", "ONLINE_SAFETY", "WELLBEING",
			"TUTOR_CONDUCT", "DATA_BREACH", "OTHER" }) String category,
			@NotBlank @Schema(allowableValues = { "LOW", "MEDIUM", "HIGH", "CRITICAL" }) String severity,
			@NotBlank @Size(min = 10, max = 8000) String narrative, UUID concernedStudentId,
			@Size(max = 20) List<UUID> involvedUserIds) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "ConcernRequest[redacted]";
		}

	}

	@Schema(name = "SafeguardingIncidentCreated", description = "Only the id: whoever raises a concern cannot read it back")
	public record IncidentCreated(UUID id) {
	}

	@Schema(name = "SafeguardingIncidentSummary", description = "Metadata only: never any text")
	public record IncidentSummary(UUID id, UUID schoolId, String category, String severity, String status, UUID assignedTo,
			UUID concernedStudentId, UUID sourceCaseId, boolean legalHold, Instant guardianInformedAt, Instant referredAt,
			Instant createdAt, Instant updatedAt, Instant closedAt) {
	}

	@Schema(name = "SafeguardingIncidentOpenRequest")
	public record OpenRequest(@NotNull ReadReason reason) {
	}

	@Schema(name = "SafeguardingIncidentDetail", description = "The decrypted incident: only ever returned by the audited open call")
	public record IncidentDetail(IncidentSummary summary, String narrative, List<UUID> involvedUserIds, String referredTo,
			List<NoteView> notes) {
	}

	@Schema(name = "SafeguardingNoteView")
	public record NoteView(UUID id, UUID authorId, String kind, String statusFrom, String statusTo, String note,
			UUID attachmentMediaId, Instant createdAt) {
	}

	@Schema(name = "SafeguardingNoteRequest")
	public record NoteRequest(@NotBlank @Size(min = 1, max = 8000) String note, UUID attachmentMediaId) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "NoteRequest[redacted]";
		}

	}

	@Schema(name = "SafeguardingStatusRequest", description = """
			Move the incident along (OPEN, INVESTIGATING, ACTION_TAKEN, REFERRED, CLOSED). Closing needs a closing note and \
			re-opening a closed incident needs a reason (both at least 10 characters); other changes may carry a note.""")
	public record StatusRequest(@NotBlank String status, @Size(max = 8000) String note) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "StatusRequest[redacted]";
		}

	}

	@Schema(name = "SafeguardingAssignRequest", description = "The assignee must be a safeguarding lead of the school (or a platform lead)")
	public record AssignRequest(@NotNull UUID assigneeId) {
	}

	@Schema(name = "SafeguardingGuardianInformedRequest", description = """
			A human recorded that the child's guardians were told (the system never tells them). {@code at} defaults to now; it \
			cannot be in the future.""")
	public record GuardianInformedRequest(Instant at, @Size(max = 8000) String note) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "GuardianInformedRequest[redacted]";
		}

	}

	@Schema(name = "SafeguardingReferralRequest", description = "A human recorded a referral to an authority (encrypted free text)")
	public record ReferralRequest(@NotBlank @Size(min = 1, max = 500) String referredTo, Instant at,
			@Size(max = 8000) String note) {

		/** Text people wrote never reaches a log. */
		@Override
		public String toString() {
			return "ReferralRequest[redacted]";
		}

	}

	@Schema(name = "SafeguardingLegalHoldRequest")
	public record LegalHoldRequest(@NotNull Boolean held) {
	}

	@Schema(name = "SafeguardingLegalHoldView")
	public record LegalHoldView(UUID id, boolean legalHold) {
	}

	@Schema(name = "SafeguardingLeadView")
	public record LeadView(UUID userId, String displayName, Instant since) {
	}

	@Schema(name = "SafeguardingLeadRequest", description = "An ACTIVE staff member (teacher, principal, other staff) of the school")
	public record LeadRequest(@NotNull UUID userId) {
	}

	@Schema(name = "SafeguardingCapabilityView", description = """
			What UIs need to show the requirement of decision 3: a school needs at least one safeguarding lead before it can \
			switch on student messaging or student contributions (the settings change is refused with \
			safeguarding.lead_required until then).""")
	public record CapabilityView(boolean hasLead, int leadCount, boolean leadRequiredForStudentFeatures,
			List<String> guardedSettings, boolean studentMessagingEnabled, boolean studentContributionsEnabled,
			boolean studentFeaturesOnWithoutLead) {
	}

	/** Optional list filters (not a body). */
	public record IncidentFilter(String status, String severity, String category) {
	}

}
