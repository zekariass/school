package com.nemaz.school.safeguarding.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.safeguarding.application.IncidentService;
import com.nemaz.school.safeguarding.application.SafeguardingModels.AssignRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.ConcernRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.GuardianInformedRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentCreated;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentDetail;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentFilter;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentSummary;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LegalHoldRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LegalHoldView;
import com.nemaz.school.safeguarding.application.SafeguardingModels.NoteRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.NoteView;
import com.nemaz.school.safeguarding.application.SafeguardingModels.OpenRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.ReferralRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.StatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Safeguarding incidents (docs/Safeguarding_Rules.md S4). Everybody who may not read incidents is answered 404
 * {@code safeguarding.not_found}, as if none existed. Nothing in a list or a summary is text; the narrative and the notes
 * come back only from the audited, rate-limited {@code open} call.
 */
@RestController
@Tag(name = "safeguarding")
public class IncidentController {

	private final IncidentService incidents;

	IncidentController(IncidentService incidents) {
		this.incidents = incidents;
	}

	@PostMapping("/api/v1/schools/{schoolId}/safeguarding/concerns")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "raiseSafeguardingConcern", summary = """
			Raise a concern (an ACTIVE staff member or teacher of the school; anybody else gets 404). Answers the incident id only: \
			the narrative is encrypted at once and only safeguarding leads can read it. HIGH and CRITICAL concerns alert the school's \
			safeguarding leads (the platform leads when it has none) with a generic text. Nobody else is ever notified automatically.""")
	public IncidentCreated raise(@PathVariable UUID schoolId, @Valid @RequestBody ConcernRequest request) {
		return this.incidents.raise(CurrentUser.require(), schoolId, request);
	}

	@GetMapping("/api/v1/schools/{schoolId}/safeguarding/incidents")
	@Operation(operationId = "listSafeguardingIncidents", summary = """
			Incidents of the school (metadata only: ids, category, severity, status, dates; never any text, and there is no search \
			over narratives), newest first, keyset paged. Safeguarding leads of the school and platform leads only: everybody \
			else gets 404 as if nothing existed. Audited (count).""")
	public CursorPage<IncidentSummary> list(@PathVariable UUID schoolId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String severity, @RequestParam(required = false) String category,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.incidents.listOfSchool(CurrentUser.require(), schoolId, new IncidentFilter(status, severity, category),
				cursor, limit);
	}

	@GetMapping("/api/v1/platform/safeguarding/incidents")
	@Operation(operationId = "listPlatformSafeguardingIncidents", summary = """
			Incidents across schools for platform safeguarding leads (optionally one school). Metadata only. Everybody else, \
			platform administrators included, gets 404.""")
	public CursorPage<IncidentSummary> listPlatform(@RequestParam(required = false) UUID schoolId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String severity,
			@RequestParam(required = false) String category, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.incidents.listForPlatform(CurrentUser.require(), schoolId, new IncidentFilter(status, severity, category),
				cursor, limit);
	}

	@GetMapping("/api/v1/safeguarding/incidents/{incidentId}")
	@Operation(operationId = "getSafeguardingIncident", summary = "Metadata of one incident (no text). Leads only, 404 for everybody else. Audited")
	public IncidentSummary get(@PathVariable UUID incidentId) {
		return this.incidents.summary(CurrentUser.require(), incidentId);
	}

	@PostMapping("/api/v1/safeguarding/incidents/{incidentId}/open")
	@Operation(operationId = "openSafeguardingIncident", summary = """
			Open an incident: the decrypted narrative and every note. A reason from the pick-list is required (TRIAGE, INVESTIGATION, \
			FOLLOW_UP, REFERRAL, OVERSIGHT, LEGAL_REQUEST); every open is audited (who, when, which incident, reason, number of notes: \
			never text) and rate limited (60 an hour). A lead who is involved in the incident, or the guardian of its student, gets 404.""")
	public IncidentDetail open(@PathVariable UUID incidentId, @Valid @RequestBody OpenRequest request) {
		return this.incidents.open(CurrentUser.require(), incidentId, request.reason());
	}

	@PostMapping("/api/v1/safeguarding/incidents/{incidentId}/notes")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "addSafeguardingNote", summary = "Add a note (encrypted), optionally with an attachment uploaded under the SAFEGUARDING media purpose. Append-only")
	public NoteView addNote(@PathVariable UUID incidentId, @Valid @RequestBody NoteRequest request) {
		return this.incidents.addNote(CurrentUser.require(), incidentId, request);
	}

	@PostMapping("/api/v1/safeguarding/incidents/{incidentId}/status")
	@Operation(operationId = "changeSafeguardingIncidentStatus", summary = """
			Move the incident along: OPEN, INVESTIGATING, ACTION_TAKEN or REFERRED, CLOSED. Closing needs a closing note, re-opening \
			a closed incident a reason (10 or more characters); each change is an append-only row.""")
	public IncidentSummary status(@PathVariable UUID incidentId, @Valid @RequestBody StatusRequest request) {
		return this.incidents.changeStatus(CurrentUser.require(), incidentId, request);
	}

	@PostMapping("/api/v1/safeguarding/incidents/{incidentId}/assign")
	@Operation(operationId = "assignSafeguardingIncident", summary = "Assign the incident to a safeguarding lead of the school (or a platform lead); anybody else is refused")
	public IncidentSummary assign(@PathVariable UUID incidentId, @Valid @RequestBody AssignRequest request) {
		return this.incidents.assign(CurrentUser.require(), incidentId, request);
	}

	@PostMapping("/api/v1/safeguarding/incidents/{incidentId}/guardian-informed")
	@Operation(operationId = "recordSafeguardingGuardianInformed", summary = """
			A human records that the child's guardians were told (guardian_informed_at). The system never tells guardians; whether and \
			when they must be told is a legal question. Audited.""")
	public IncidentSummary guardianInformed(@PathVariable UUID incidentId, @Valid @RequestBody GuardianInformedRequest request) {
		return this.incidents.recordGuardianInformed(CurrentUser.require(), incidentId, request);
	}

	@PostMapping("/api/v1/safeguarding/incidents/{incidentId}/referral")
	@Operation(operationId = "recordSafeguardingReferral", summary = "A human records a referral to an authority (referred_at and the encrypted referred_to text). Audited")
	public IncidentSummary referral(@PathVariable UUID incidentId, @Valid @RequestBody ReferralRequest request) {
		return this.incidents.recordReferral(CurrentUser.require(), incidentId, request);
	}

	@PutMapping("/api/v1/safeguarding/incidents/{incidentId}/legal-hold")
	@Operation(operationId = "setSafeguardingIncidentLegalHold", summary = "Set or lift the legal hold of an incident (leads). A hold blocks the retention purge and every erasure. Audited")
	public LegalHoldView legalHold(@PathVariable UUID incidentId, @Valid @RequestBody LegalHoldRequest request) {
		return this.incidents.setLegalHold(CurrentUser.require(), incidentId, request.held());
	}

	@PutMapping("/api/v1/platform/safeguarding/incidents/{incidentId}/legal-hold")
	@PreAuthorize("@authz.platform('platform.moderation.manage')")
	@Operation(operationId = "setPlatformSafeguardingIncidentLegalHold", summary = """
			Set or lift the legal hold of an incident as a platform moderator or administrator, who cannot read incidents: the answer \
			carries the id and the flag only. Audited.""")
	public LegalHoldView platformLegalHold(@PathVariable UUID incidentId, @Valid @RequestBody LegalHoldRequest request) {
		return this.incidents.setLegalHoldAsPlatform(CurrentUser.require(), incidentId, request.held());
	}

}
