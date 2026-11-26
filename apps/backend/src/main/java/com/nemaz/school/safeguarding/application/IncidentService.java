package com.nemaz.school.safeguarding.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.text.PlainText;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.safeguarding.IncidentHolds;
import com.nemaz.school.safeguarding.IncidentIntake;
import com.nemaz.school.safeguarding.application.IncidentRows.IncidentRow;
import com.nemaz.school.safeguarding.application.SafeguardingModels.AssignRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.ConcernRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.GuardianInformedRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentCreated;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentDetail;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentFilter;
import com.nemaz.school.safeguarding.application.SafeguardingModels.IncidentSummary;
import com.nemaz.school.safeguarding.application.SafeguardingModels.LegalHoldView;
import com.nemaz.school.safeguarding.application.SafeguardingModels.NoteRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.NoteView;
import com.nemaz.school.safeguarding.application.SafeguardingModels.ReadReason;
import com.nemaz.school.safeguarding.application.SafeguardingModels.ReferralRequest;
import com.nemaz.school.safeguarding.application.SafeguardingModels.StatusRequest;
import com.nemaz.school.safeguarding.infrastructure.IncidentCipher;
import com.nemaz.school.system.RateLimiter;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safeguarding incidents (docs/Safeguarding_Rules.md S4). The narrative, every note and the referral text are encrypted
 * before they reach the database ({@link IncidentCipher}) and never appear anywhere else: not in a list, an audit row, an
 * event, a log line or a notification. Every change is a note or a status row, append-only. Nothing here tells anybody
 * outside the leads about an incident: {@code guardian_informed_at} and {@code referred_at} are set by a human, and audited.
 */
@Service
public class IncidentService implements IncidentIntake, IncidentHolds {

	static final int OPENS_PER_HOUR = 60;

	static final int LISTS_PER_HOUR = 120;

	static final int CONCERNS_PER_DAY = 30;

	static final int MIN_NOTE = 10;

	private final JdbcClient jdbc;

	private final IncidentCipher cipher;

	private final IncidentAccess access;

	private final LeadDirectory leads;

	private final AccessControl accessControl;

	private final AuditLog audit;

	private final RateLimiter limiter;

	private final NotificationService notifications;

	private final MediaService media;

	private final TenantDirectory directory;

	private final Clock clock;

	IncidentService(JdbcClient jdbc, IncidentCipher cipher, IncidentAccess access, LeadDirectory leads,
			AccessControl accessControl, AuditLog audit, RateLimiter limiter, NotificationService notifications,
			MediaService media, TenantDirectory directory, Clock clock) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.access = access;
		this.leads = leads;
		this.accessControl = accessControl;
		this.audit = audit;
		this.limiter = limiter;
		this.notifications = notifications;
		this.media = media;
		this.directory = directory;
		this.clock = clock;
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private static String narrativeAad(UUID incidentId) {
		return "incident:" + incidentId + ":narrative";
	}

	private static String referralAad(UUID incidentId) {
		return "incident:" + incidentId + ":referral";
	}

	private static String noteAad(UUID noteId) {
		return "note:" + noteId;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// raising

	/**
	 * "Raise a concern": an ACTIVE staff member or teacher of the school (leads too). Whoever is not gets 404, so a student
	 * or guardian learns nothing. Returns the id only; the raiser cannot read it back.
	 */
	@Transactional
	public IncidentCreated raise(CurrentUser user, UUID schoolId, ConcernRequest request) {
		boolean staff = this.leads.isActiveStaff(user.userId(), schoolId);
		if (!staff && !this.access.canManage(user, schoolId)) {
			throw IncidentAccess.notFound();
		}
		this.limiter.consume("safeguarding-raise", user.userId().toString(), CONCERNS_PER_DAY, Duration.ofDays(1),
				"safeguarding.rate_limited");
		String category = enumValue(request.category(), SafeguardingModels.CATEGORIES, "safeguarding.category_invalid");
		String severity = enumValue(request.severity(), SafeguardingModels.SEVERITIES, "safeguarding.severity_invalid");
		String narrative = PlainText.clean(request.narrative());
		if (narrative.length() < 10) {
			throw ApiException.badRequest("safeguarding.narrative_required");
		}
		if (narrative.length() > 8000) {
			throw ApiException.badRequest("safeguarding.narrative_too_long");
		}
		UUID student = request.concernedStudentId();
		if (student != null && !this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st)")
			.param("s", schoolId)
			.param("st", student)
			.query(Boolean.class)
			.single()) {
			throw ApiException.badRequest("safeguarding.student_invalid");
		}
		List<UUID> involved = distinct(request.involvedUserIds());
		requireUsers(involved);
		UUID id = insert(schoolId, user.userId(), null, student, involved, category, severity, narrative);
		this.audit.record(AuditEntry.of("safeguarding.incident.create", AuditOutcome.SUCCESS)
			.target("incident", id)
			.school(schoolId)
			.metadata("category", category)
			.metadata("severity", severity));
		alertIfSerious(id, schoolId, severity, user.userId());
		return new IncidentCreated(id);
	}

	/** Escalation of a moderation case, idempotent per case (see {@link IncidentIntake}). */
	@Override
	@Transactional
	public UUID raiseFromCase(UUID schoolId, UUID caseId, UUID actorId, String category, String severity, String narrative) {
		UUID existing = this.jdbc.sql("select id from safeguarding_incidents where source_case_id = :c order by created_at limit 1")
			.param("c", caseId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (existing != null) {
			return existing;
		}
		String cat = enumValue(category, SafeguardingModels.CATEGORIES, "safeguarding.category_invalid");
		String sev = enumValue(severity, SafeguardingModels.SEVERITIES, "safeguarding.severity_invalid");
		String text = PlainText.clean(narrative);
		UUID id = insert(schoolId, actorId, caseId, null, List.of(), cat, sev, text);
		this.audit.record(AuditEntry.of("safeguarding.incident.create", AuditOutcome.SUCCESS)
			.target("incident", id)
			.school(schoolId)
			.metadata("category", cat)
			.metadata("severity", sev)
			.metadata("fromCase", caseId.toString()));
		alertIfSerious(id, schoolId, sev, actorId);
		return id;
	}

	private UUID insert(UUID schoolId, UUID raisedBy, UUID caseId, UUID student, List<UUID> involved, String category,
			String severity, String narrative) {
		UUID id = Ids.newId();
		String encrypted = this.cipher.encrypt(narrative, narrativeAad(id));
		Timestamp now = Timestamp.from(now());
		this.jdbc.sql("""
				insert into safeguarding_incidents (id, school_id, raised_by, source_case_id, concerned_student_id,
				       involved_user_ids, category, severity, narrative_enc, created_at, updated_at)
				values (:id, :school, :by, :case, :student, cast(cast(:involved as text) as uuid[]), :category, :severity, :narrative, :now, :now)""")
			.param("id", id)
			.param("school", schoolId)
			.param("by", raisedBy)
			.param("case", caseId)
			.param("student", student)
			.param("involved", arrayLiteral(involved))
			.param("category", category)
			.param("severity", severity)
			.param("narrative", encrypted)
			.param("now", now)
			.update();
		this.jdbc.sql("""
				insert into safeguarding_incident_notes (id, incident_id, author_id, kind, status_to, created_at)
				values (:id, :incident, :by, 'STATUS', 'OPEN', :now)""")
			.param("id", Ids.newId())
			.param("incident", id)
			.param("by", raisedBy)
			.param("now", now)
			.update();
		return id;
	}

	/** HIGH and CRITICAL alert the school's leads (the platform leads when it has none): a generic text, never a name or content. */
	private void alertIfSerious(UUID incidentId, UUID schoolId, String severity, UUID raisedBy) {
		if (!"HIGH".equals(severity) && !"CRITICAL".equals(severity)) {
			return;
		}
		Set<UUID> recipients = new LinkedHashSet<>(this.leads.alertRecipients(schoolId));
		recipients.remove(raisedBy);
		recipients.removeIf((u) -> this.notifications.alreadyNotified(u, "SAFEGUARDING_ALERT", "safeguarding_incident", incidentId));
		if (recipients.isEmpty()) {
			return;
		}
		String slug = (schoolId != null) ? this.directory.school(schoolId).map(TenantDirectory.SchoolRef::slug).orElse(null) : null;
		NotificationRequest.Builder request = NotificationRequest.to("SAFEGUARDING_ALERT", recipients)
			.school(schoolId)
			.source("safeguarding_incident", incidentId)
			.path((slug != null) ? "/s/" + slug + "/safeguarding" : "/safeguarding");
		this.notifications.notify(request.build());
	}

	// ---------------------------------------------------------------------------------------------------------------
	// reading

	/** Metadata of the incidents of a school the caller leads (platform leads: any), keyset paged. Never text. */
	@Transactional
	public CursorPage<IncidentSummary> listOfSchool(CurrentUser user, UUID schoolId, IncidentFilter filter, String cursor,
			Integer limit) {
		this.access.requireReader(user, schoolId);
		this.limiter.consume("safeguarding-list", user.userId().toString(), LISTS_PER_HOUR, Duration.ofHours(1),
				"safeguarding.rate_limited");
		CursorPage<IncidentSummary> page = page(user, "i.school_id = :school", Map.of("school", schoolId), filter, cursor, limit);
		this.audit.record(AuditEntry.of("safeguarding.incident.list", AuditOutcome.SUCCESS)
			.school(schoolId)
			.metadata("count", page.items().size()));
		return page;
	}

	/** The platform leads' list across schools (optionally one school, or the platform-scope incidents). */
	@Transactional
	public CursorPage<IncidentSummary> listForPlatform(CurrentUser user, UUID schoolId, IncidentFilter filter, String cursor,
			Integer limit) {
		AccessibleScopes readable = this.accessControl.accessible(user, Permissions.SCHOOL_SAFEGUARDING_READ);
		if (!readable.platform()) {
			throw IncidentAccess.notFound();
		}
		this.limiter.consume("safeguarding-list", user.userId().toString(), LISTS_PER_HOUR, Duration.ofHours(1),
				"safeguarding.rate_limited");
		CursorPage<IncidentSummary> page = (schoolId != null)
				? page(user, "i.school_id = :school", Map.of("school", schoolId), filter, cursor, limit)
				: page(user, "true", Map.of(), filter, cursor, limit);
		this.audit.record(AuditEntry.of("safeguarding.incident.list", AuditOutcome.SUCCESS)
			.school(schoolId)
			.metadata("count", page.items().size())
			.metadata("platform", true));
		return page;
	}

	private CursorPage<IncidentSummary> page(CurrentUser user, String where, Map<String, Object> params, IncidentFilter filter,
			String cursor, Integer limit) {
		int size = Cursors.clampLimit(limit);
		Timestamp cursorAt = null;
		UUID cursorId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				cursorAt = Timestamp.from(Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS));
				cursorId = UUID.fromString(parts.get(1));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		var statement = this.jdbc.sql("select " + IncidentRows.COLUMNS + " from safeguarding_incidents i where " + where + """
				 and (cast(:status as text) is null or i.status = :status)
				 and (cast(:severity as text) is null or i.severity = :severity)
				 and (cast(:category as text) is null or i.category = :category)
				 and (cast(:cursor_at as timestamptz) is null or (i.created_at, i.id) < (cast(:cursor_at as timestamptz), cast(:cursor_id as uuid)))
				order by i.created_at desc, i.id desc limit :n""")
			.param("status", blankToNull(filter.status()))
			.param("severity", blankToNull(filter.severity()))
			.param("category", blankToNull(filter.category()))
			.param("cursor_at", cursorAt)
			.param("cursor_id", cursorId)
			.param("n", size + 1);
		for (Map.Entry<String, Object> p : params.entrySet()) {
			statement = statement.param(p.getKey(), p.getValue());
		}
		List<IncidentRow> rows = statement.query(IncidentRows::incident).list();
		boolean more = rows.size() > size;
		List<IncidentRow> visible = (more ? rows.subList(0, size) : rows).stream()
			.filter((r) -> !this.access.conflicted(user.userId(), r))
			.toList();
		String next = more ? Cursors.encode(String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, rows.get(size - 1).createdAt())),
				rows.get(size - 1).id().toString()) : null;
		return new CursorPage<>(visible.stream().map(IncidentRow::summary).toList(), next);
	}

	/** Metadata of one incident (no text), audited. */
	@Transactional
	public IncidentSummary summary(CurrentUser user, UUID incidentId) {
		IncidentRow row = this.access.readable(user, incidentId);
		this.audit.record(AuditEntry.of("safeguarding.incident.view", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId()));
		return row.summary();
	}

	/**
	 * Opens the incident: the narrative and every note, decrypted. Needs a reason from the pick-list, is rate limited and
	 * audited (who, when, which incident, which reason, how many notes: never text).
	 */
	@Transactional
	public IncidentDetail open(CurrentUser user, UUID incidentId, ReadReason reason) {
		IncidentRow row = this.access.readable(user, incidentId);
		this.limiter.consume("safeguarding-open", user.userId().toString(), OPENS_PER_HOUR, Duration.ofHours(1),
				"safeguarding.rate_limited");
		List<NoteView> notes = this.jdbc.sql("""
				select id, author_id, kind, status_from, status_to, note_enc, attachment_media_id, created_at
				from safeguarding_incident_notes where incident_id = :i order by created_at, id""")
			.param("i", incidentId)
			.query((rs, i) -> {
				UUID noteId = Rows.uuid(rs, "id");
				return new NoteView(noteId, Rows.uuid(rs, "author_id"), rs.getString("kind"), rs.getString("status_from"),
						rs.getString("status_to"), this.cipher.decrypt(rs.getString("note_enc"), noteAad(noteId)),
						Rows.uuid(rs, "attachment_media_id"), Rows.instant(rs, "created_at"));
			})
			.list();
		IncidentDetail detail = new IncidentDetail(row.summary(), this.cipher.decrypt(row.narrativeEnc(), narrativeAad(incidentId)),
				row.involved(), this.cipher.decrypt(row.referredToEnc(), referralAad(incidentId)), notes);
		this.audit.record(AuditEntry.of("safeguarding.incident.open", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId())
			.metadata("reason", reason.name())
			.metadata("notes", notes.size()));
		return detail;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// working an incident

	@Transactional
	public NoteView addNote(CurrentUser user, UUID incidentId, NoteRequest request) {
		IncidentRow row = this.access.manageable(user, incidentId);
		String text = PlainText.clean(request.note());
		if (text.isEmpty()) {
			throw ApiException.badRequest("safeguarding.note_required");
		}
		if (text.length() > 8000) {
			throw ApiException.badRequest("safeguarding.note_too_long");
		}
		UUID mediaId = request.attachmentMediaId();
		if (mediaId != null) {
			if (row.schoolId() == null) {
				throw ApiException.badRequest("safeguarding.attachment_unsupported");
			}
			this.media.requireAttachable(mediaId, MediaPurpose.SAFEGUARDING, MediaContext.school(row.schoolId()), user);
		}
		UUID noteId = writeNote(incidentId, user.userId(), mediaId != null ? "ATTACHMENT" : "NOTE", null, null, text, mediaId);
		touch(incidentId);
		this.audit.record(AuditEntry.of("safeguarding.incident.note", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId())
			.metadata("attachment", mediaId != null));
		return new NoteView(noteId, user.userId(), mediaId != null ? "ATTACHMENT" : "NOTE", null, null, text, mediaId, now());
	}

	@Transactional
	public IncidentSummary changeStatus(CurrentUser user, UUID incidentId, StatusRequest request) {
		IncidentRow row = this.access.manageable(user, incidentId);
		String to = enumValue(request.status(), SafeguardingModels.STATUSES, "safeguarding.status_invalid");
		String from = row.status();
		if (from.equals(to)) {
			return row.summary();
		}
		String note = (request.note() == null) ? "" : PlainText.clean(request.note());
		if (note.length() > 8000) {
			throw ApiException.badRequest("safeguarding.note_too_long");
		}
		String kind = "STATUS";
		if ("CLOSED".equals(to)) {
			if (note.length() < MIN_NOTE) {
				throw ApiException.badRequest("safeguarding.closing_note_required");
			}
			kind = "CLOSE";
		}
		else if ("CLOSED".equals(from)) {
			if (note.length() < MIN_NOTE) {
				throw ApiException.badRequest("safeguarding.reopen_reason_required");
			}
			if ("OPEN".equals(to)) {
				// re-opening goes back to work, never to the unstarted state
				throw ApiException.badRequest("safeguarding.status_invalid");
			}
			kind = "REOPEN";
		}
		else if (!allowed(from, to)) {
			throw ApiException.conflict("safeguarding.status_transition_invalid");
		}
		Timestamp now = Timestamp.from(now());
		this.jdbc.sql("""
				update safeguarding_incidents set status = cast(:to as text),
				       closed_at = case when cast(:to as text) = 'CLOSED' then cast(:now as timestamptz) else null end,
				       updated_at = :now where id = :id""")
			.param("to", to)
			.param("now", now)
			.param("id", incidentId)
			.update();
		writeNote(incidentId, user.userId(), kind, from, to, note.isEmpty() ? null : note, null);
		this.audit.record(AuditEntry.of("safeguarding.incident.status", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId())
			.metadata("from", from)
			.metadata("to", to));
		return this.access.find(incidentId).summary();
	}

	private static boolean allowed(String from, String to) {
		return switch (from) {
			case "OPEN" -> "INVESTIGATING".equals(to);
			case "INVESTIGATING" -> "ACTION_TAKEN".equals(to) || "REFERRED".equals(to);
			case "ACTION_TAKEN" -> "REFERRED".equals(to) || "INVESTIGATING".equals(to);
			case "REFERRED" -> "ACTION_TAKEN".equals(to) || "INVESTIGATING".equals(to);
			default -> false;
		};
	}

	@Transactional
	public IncidentSummary assign(CurrentUser user, UUID incidentId, AssignRequest request) {
		IncidentRow row = this.access.manageable(user, incidentId);
		UUID assignee = request.assigneeId();
		if (!this.leads.isLeadFor(assignee, row.schoolId()) || this.access.conflicted(assignee, row)) {
			throw ApiException.badRequest("safeguarding.assignee_invalid");
		}
		if (assignee.equals(row.assignedTo())) {
			return row.summary();
		}
		this.jdbc.sql("update safeguarding_incidents set assigned_to = :a, updated_at = :now where id = :id")
			.param("a", assignee)
			.param("now", Timestamp.from(now()))
			.param("id", incidentId)
			.update();
		this.jdbc.sql("""
				insert into safeguarding_incident_notes (id, incident_id, author_id, kind, status_to, created_at)
				values (:id, :i, :by, 'ASSIGNMENT', :a, :now)""")
			.param("id", Ids.newId())
			.param("i", incidentId)
			.param("by", user.userId())
			.param("a", assignee.toString())
			.param("now", Timestamp.from(now()))
			.update();
		this.audit.record(AuditEntry.of("safeguarding.incident.assign", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId())
			.metadata("assigneeId", assignee.toString()));
		return this.access.find(incidentId).summary();
	}

	/** A human recorded that the child's guardians were told. The system never tells them (S4.8). */
	@Transactional
	public IncidentSummary recordGuardianInformed(CurrentUser user, UUID incidentId, GuardianInformedRequest request) {
		IncidentRow row = this.access.manageable(user, incidentId);
		Instant at = recordedAt(request.at(), row);
		String note = optionalNote(request.note());
		this.jdbc.sql("update safeguarding_incidents set guardian_informed_at = :at, updated_at = :now where id = :id")
			.param("at", Timestamp.from(at))
			.param("now", Timestamp.from(now()))
			.param("id", incidentId)
			.update();
		writeNote(incidentId, user.userId(), "GUARDIAN_INFORMED", null, null, note, null);
		this.audit.record(AuditEntry.of("safeguarding.incident.guardian_informed", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId()));
		return this.access.find(incidentId).summary();
	}

	/** A human recorded a referral to an authority (the text is encrypted). */
	@Transactional
	public IncidentSummary recordReferral(CurrentUser user, UUID incidentId, ReferralRequest request) {
		IncidentRow row = this.access.manageable(user, incidentId);
		Instant at = recordedAt(request.at(), row);
		String referredTo = PlainText.clean(request.referredTo());
		if (referredTo.isEmpty() || referredTo.length() > 500) {
			throw ApiException.badRequest("safeguarding.referral_invalid");
		}
		String note = optionalNote(request.note());
		this.jdbc.sql("""
				update safeguarding_incidents set referred_to_enc = :enc, referred_at = :at, updated_at = :now where id = :id""")
			.param("enc", this.cipher.encrypt(referredTo, referralAad(incidentId)))
			.param("at", Timestamp.from(at))
			.param("now", Timestamp.from(now()))
			.param("id", incidentId)
			.update();
		writeNote(incidentId, user.userId(), "REFERRAL", null, null, note, null);
		this.audit.record(AuditEntry.of("safeguarding.incident.referral", AuditOutcome.SUCCESS)
			.target("incident", incidentId)
			.school(row.schoolId()));
		return this.access.find(incidentId).summary();
	}

	/** Legal hold, set or lifted by a lead (audited). A hold blocks the retention purge and every erasure. */
	@Transactional
	public LegalHoldView setLegalHold(CurrentUser user, UUID incidentId, boolean held) {
		IncidentRow row = this.access.manageable(user, incidentId);
		return applyHold(user.userId(), row, held);
	}

	/**
	 * Legal hold set or lifted by a platform administrator who cannot read incidents: the answer carries the id and the flag
	 * only. The caller (a holder of {@code platform.moderation.manage}) has been checked by the controller.
	 */
	@Transactional
	public LegalHoldView setLegalHoldAsPlatform(CurrentUser user, UUID incidentId, boolean held) {
		IncidentRow row = this.access.find(incidentId);
		if (row == null) {
			throw IncidentAccess.notFound();
		}
		return applyHold(user.userId(), row, held);
	}

	private LegalHoldView applyHold(UUID actor, IncidentRow row, boolean held) {
		if (row.legalHold() != held) {
			Timestamp now = Timestamp.from(now());
			this.jdbc.sql("""
					update safeguarding_incidents set legal_hold = :held, legal_hold_by = :by, legal_hold_at = :now, updated_at = :now
					where id = :id""")
				.param("held", held)
				.param("by", actor)
				.param("now", now)
				.param("id", row.id())
				.update();
			writeNote(row.id(), actor, held ? "HOLD_SET" : "HOLD_LIFTED", null, null, null, null);
			this.audit.record(AuditEntry.of(held ? "safeguarding.incident.hold_set" : "safeguarding.incident.hold_lifted",
					AuditOutcome.SUCCESS)
				.target("incident", row.id())
				.school(row.schoolId()));
		}
		return new LegalHoldView(row.id(), held);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean userInHeldIncident(UUID userId) {
		return this.jdbc.sql("""
				select exists (select 1 from safeguarding_incidents where legal_hold
				  and (concerned_student_id = :u or raised_by = :u or :u = any (involved_user_ids)))""")
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// helpers

	private UUID writeNote(UUID incidentId, UUID author, String kind, String from, String to, String text, UUID mediaId) {
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into safeguarding_incident_notes (id, incident_id, author_id, kind, status_from, status_to, note_enc,
				       attachment_media_id, created_at)
				values (:id, :i, :by, :kind, :from, :to, :note, :media, :now)""")
			.param("id", id)
			.param("i", incidentId)
			.param("by", author)
			.param("kind", kind)
			.param("from", from)
			.param("to", to)
			.param("note", (text == null || text.isEmpty()) ? null : this.cipher.encrypt(text, noteAad(id)))
			.param("media", mediaId)
			.param("now", Timestamp.from(now()))
			.update();
		return id;
	}

	private void touch(UUID incidentId) {
		this.jdbc.sql("update safeguarding_incidents set updated_at = :now where id = :id")
			.param("now", Timestamp.from(now()))
			.param("id", incidentId)
			.update();
	}

	private Instant recordedAt(Instant at, IncidentRow row) {
		Instant value = (at != null) ? at.truncatedTo(ChronoUnit.MICROS) : now();
		if (value.isAfter(now().plusSeconds(60)) || value.isBefore(row.createdAt().minus(Duration.ofDays(3650)))) {
			throw ApiException.badRequest("safeguarding.date_invalid");
		}
		return value;
	}

	private static String optionalNote(String raw) {
		String note = (raw == null) ? "" : PlainText.clean(raw);
		if (note.length() > 8000) {
			throw ApiException.badRequest("safeguarding.note_too_long");
		}
		return note.isEmpty() ? null : note;
	}

	private void requireUsers(List<UUID> ids) {
		if (ids.isEmpty()) {
			return;
		}
		int found = this.jdbc.sql("select count(*) from users where id in (:ids)").param("ids", ids).query(Integer.class).single();
		if (found != ids.size()) {
			throw ApiException.badRequest("safeguarding.involved_invalid");
		}
	}

	private static List<UUID> distinct(List<UUID> ids) {
		return (ids == null) ? List.of() : new ArrayList<>(new LinkedHashSet<>(ids));
	}

	private static String arrayLiteral(List<UUID> ids) {
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < ids.size(); i++) {
			sb.append(i == 0 ? "" : ",").append(ids.get(i));
		}
		return sb.append('}').toString();
	}

	private static String enumValue(String value, List<String> allowed, String code) {
		if (value == null || !allowed.contains(value)) {
			throw ApiException.badRequest(code);
		}
		return value;
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

}
