package com.nemaz.school.guardians.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianLinkChanged;
import com.nemaz.school.guardians.GuardianLinkChanged.Change;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.identity.UserAccounts.ProvisionRequest;
import com.nemaz.school.identity.UserAccounts.ProvisionedUser;
import com.nemaz.school.system.EmailSender;
import com.nemaz.school.system.SmsSender;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guardian links (R-29): requests from invitations, the verification queue, management by the school and links the
 * school creates itself.
 * <ul>
 * <li><b>Request:</b> a new link is PENDING (it grants nothing) unless the caller passes an auto-verification
 * ({@code SCHOOL_INVITE}); the same adult and child never get a second live link (the existing one is returned).
 * Verifiers are notified ({@code GUARDIAN_LINK_REQUESTED}).</li>
 * <li><b>Approve / reject:</b> verifiers only ({@link GuardianRules}); approval records {@code SCHOOL_VERIFIED}, the
 * verifier, the time and the school. The guardian is notified unless the link is custody-restricted.</li>
 * <li><b>Manage:</b> {@code school.guardians.manage}: permission flags, relationship, primary guardian (one per child),
 * custody restriction with an admin-only note, revoke (immediate: access is checked on every request), links created by
 * the school (the administrator vouches, as for staff).</li>
 * <li><b>Audit:</b> every change ({@code guardian_link.*}) with the changed field names only: restriction notes,
 * decision notes and contact details never reach the audit log or events.</li>
 * </ul>
 */
@Service
@Transactional
public class GuardianLinkService {

	private static final Logger log = LoggerFactory.getLogger(GuardianLinkService.class);

	private final JdbcClient jdbc;

	private final GuardianRules rules;

	private final GuardianNotifications notifications;

	private final SchoolGate gate;

	private final UserAccounts accounts;

	private final TenantDirectory directory;

	private final SmsSender sms;

	private final EmailSender email;

	private final MessageSource messages;

	private final TaskExecutor executor;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	GuardianLinkService(JdbcClient jdbc, GuardianRules rules, GuardianNotifications notifications, SchoolGate gate,
			UserAccounts accounts, TenantDirectory directory, SmsSender sms, EmailSender email, MessageSource messages,
			@Qualifier("applicationTaskExecutor") TaskExecutor executor, AuditLog audit,
			ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.rules = rules;
		this.notifications = notifications;
		this.gate = gate;
		this.accounts = accounts;
		this.directory = directory;
		this.sms = sms;
		this.email = email;
		this.messages = messages;
		this.executor = executor;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	// --- requests (invitations) -----------------------------------------------------------

	/**
	 * Links {@code guardianId} to the student, or returns the live link they already have (idempotent). With
	 * {@code autoVerifiedBy} (the inviter) the new link is ACTIVE with {@code SCHOOL_INVITE}, otherwise PENDING.
	 * Joins the caller's transaction.
	 */
	UUID request(UUID studentId, UUID guardianId, RelationshipType relationship, RequestedVia via, UUID invitationId,
			UUID schoolId, UUID autoVerifiedBy) {
		ensureProfile(guardianId);
		Optional<UUID> existing = liveLinkId(studentId, guardianId);
		if (existing.isPresent()) {
			return existing.get();
		}
		Instant now = this.clock.instant();
		UUID id = Ids.newId();
		boolean active = autoVerifiedBy != null;
		try {
			this.jdbc.sql("""
					insert into student_guardians (id, student_id, guardian_id, relationship_type, status, requested_via,
					                               invitation_id, verification_method, verified_by_school_id, verified_by,
					                               verified_at, created_at, updated_at)
					values (:id, :st, :g, :rel, :status, :via, :inv, :method, :school, :by, :at, :now, :now)""")
				.param("id", id)
				.param("st", studentId)
				.param("g", guardianId)
				.param("rel", ((relationship != null) ? relationship : RelationshipType.OTHER).name())
				.param("status", active ? "ACTIVE" : "PENDING")
				.param("via", via.name())
				.param("inv", invitationId)
				.param("method", active ? VerificationMethod.SCHOOL_INVITE.name() : null)
				.param("school", active ? schoolId : null)
				.param("by", autoVerifiedBy)
				.param("at", active ? Timestamp.from(now) : null)
				.param("now", Timestamp.from(now))
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_student_guardian_live")) {
				return liveLinkId(studentId, guardianId).orElseThrow(() -> ex);
			}
			throw ex;
		}
		this.events.publishEvent(new GuardianLinkChanged(id, studentId, guardianId, schoolId,
				active ? Change.ACTIVATED : Change.REQUESTED, guardianId));
		this.audit.record(AuditEntry.of(active ? "guardian_link.auto_verify" : "guardian_link.request", AuditOutcome.SUCCESS)
			.actor(guardianId)
			.target("guardian_link", id)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("requestedVia", via.name())
			.metadata("invitationId", (invitationId != null) ? invitationId.toString() : null)
			.metadata("status", active ? "ACTIVE" : "PENDING"));
		if (!active) {
			this.notifications.requested(id, schoolId, studentId, guardianId);
		}
		return id;
	}

	// --- verification queue -----------------------------------------------------------------

	/**
	 * Links of the school's ACTIVE students in one status (default PENDING), oldest first: every link for holders of
	 * {@code school.guardians.manage}, the links of their own classes' students for verifying LEAD teachers.
	 */
	@Transactional(readOnly = true)
	public CursorPage<GuardianLinkView> queue(CurrentUser user, UUID schoolId, LinkStatus status, String cursor,
			Integer limit) {
		boolean manager = this.rules.manages(user, schoolId);
		if (!manager) {
			this.gate.requireVisible(user, schoolId);
			if (!this.rules.isVerifyingTeacher(user, schoolId)) {
				throw ApiException.forbidden("guardian.access_denied");
			}
		}
		int size = Cursors.clampLimit(limit);
		Map<String, Object> params = new HashMap<>();
		StringBuilder where = new StringBuilder("""
				exists (select 1 from school_students ss where ss.student_id = sg.student_id and ss.school_id = :school
				        and ss.status = 'ACTIVE') and sg.status = :status""");
		params.put("school", schoolId);
		params.put("status", ((status != null) ? status : LinkStatus.PENDING).name());
		if (!manager) {
			where.append(" ").append("""
					and exists (select 1 from class_teachers ct join class_students cs on cs.class_id = ct.class_id
					             and cs.status = 'ACTIVE' where ct.teacher_id = :me and ct.status = 'ACTIVE' and ct.role = 'LEAD'
					             and ct.school_id = :school and cs.student_id = sg.student_id)""");
			params.put("me", user.userId());
		}
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			where.append(" and (sg.created_at, sg.id) > (:ccreated, :cid)");
			try {
				params.put("ccreated", Timestamp.from(Instant.parse(parts.get(0))));
				params.put("cid", UUID.fromString(parts.get(1)));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<LinkRow> rows = this.jdbc.sql(SELECT + " where " + where + " order by sg.created_at, sg.id limit " + (size + 1))
			.params(params)
			.query(GuardianLinkService::row)
			.list();
		CursorPage<LinkRow> page = CursorPage.fromOverfetch(rows, size,
				(r) -> Cursors.encode(r.createdAt().toString(), r.id().toString()));
		return new CursorPage<>(page.items().stream().map((r) -> view(r, manager)).toList(), page.nextCursor());
	}

	public GuardianLinkView approve(CurrentUser user, UUID schoolId, UUID linkId, String note) {
		LinkRow link = lockForVerification(user, schoolId, linkId);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update student_guardians set status = 'ACTIVE', verification_method = 'SCHOOL_VERIFIED',
				       verified_by_school_id = :school, verified_by = :by, verified_at = :now, decided_by = :by,
				       decided_at = :now, decision_note = :note, updated_at = :now, version = version + 1
				where id = :id""")
			.param("school", schoolId)
			.param("by", user.userId())
			.param("now", Timestamp.from(now))
			.param("note", blankToNull(note))
			.param("id", linkId)
			.update();
		this.events.publishEvent(new GuardianLinkChanged(linkId, link.studentId(), link.guardianId(), schoolId,
				Change.ACTIVATED, user.userId()));
		this.audit.record(AuditEntry.of("guardian_link.approve", AuditOutcome.SUCCESS)
			.target("guardian_link", linkId)
			.school(schoolId)
			.metadata("studentId", link.studentId().toString())
			.metadata("verificationMethod", VerificationMethod.SCHOOL_VERIFIED.name())
			.metadata("withNote", note != null && !note.isBlank()));
		if (!link.custodyRestricted()) {
			this.notifications.approved(linkId, schoolId, link.studentId(), link.guardianId());
		}
		return load(linkId, this.rules.manages(user, schoolId));
	}

	public GuardianLinkView reject(CurrentUser user, UUID schoolId, UUID linkId, String note) {
		LinkRow link = lockForVerification(user, schoolId, linkId);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update student_guardians set status = 'REJECTED', is_primary = false, decided_by = :by, decided_at = :now,
				       decision_note = :note, updated_at = :now, version = version + 1
				where id = :id""")
			.param("by", user.userId())
			.param("now", Timestamp.from(now))
			.param("note", blankToNull(note))
			.param("id", linkId)
			.update();
		this.events.publishEvent(new GuardianLinkChanged(linkId, link.studentId(), link.guardianId(), schoolId,
				Change.REJECTED, user.userId()));
		this.audit.record(AuditEntry.of("guardian_link.reject", AuditOutcome.SUCCESS)
			.target("guardian_link", linkId)
			.school(schoolId)
			.metadata("studentId", link.studentId().toString())
			.metadata("withNote", note != null && !note.isBlank()));
		if (!link.custodyRestricted()) {
			this.notifications.rejected(linkId, schoolId, link.studentId(), link.guardianId());
		}
		return load(linkId, this.rules.manages(user, schoolId));
	}

	// --- management ---------------------------------------------------------------------

	/**
	 * Links of one student: PENDING and ACTIVE for teachers of the student (no contact details, no notes), every status
	 * with contact details (masked) and notes for {@code school.guardians.manage}.
	 */
	@Transactional(readOnly = true)
	public List<GuardianLinkView> forStudent(CurrentUser user, UUID schoolId, UUID studentId) {
		this.rules.require(user, schoolId, studentId, this.rules.mayReadLinks(user, schoolId, studentId));
		boolean manager = this.rules.manages(user, schoolId);
		String statuses = manager ? "" : " and sg.status in ('PENDING', 'ACTIVE')";
		return this.jdbc.sql(SELECT + " where sg.student_id = :st" + statuses
				+ " order by case sg.status when 'ACTIVE' then 0 when 'PENDING' then 1 else 2 end, sg.is_primary desc, sg.created_at, sg.id")
			.param("st", studentId)
			.query(GuardianLinkService::row)
			.list()
			.stream()
			.map((r) -> view(r, manager))
			.toList();
	}

	public GuardianLinkView update(CurrentUser user, UUID schoolId, UUID linkId, LinkChange c) {
		LinkRow link = lockManaged(user, schoolId, linkId);
		if (link.status() != LinkStatus.PENDING && link.status() != LinkStatus.ACTIVE) {
			throw ApiException.conflict("guardian_link.not_live");
		}
		List<String> fields = new ArrayList<>();
		boolean restricted = (c.custodyRestricted() != null) ? c.custodyRestricted() : link.custodyRestricted();
		String note = link.restrictionNote();
		if (c.custodyRestricted() != null && c.custodyRestricted() != link.custodyRestricted()) {
			fields.add("custodyRestricted");
		}
		if (c.restrictionNote() != null) {
			note = blankToNull(c.restrictionNote());
			fields.add("restrictionNote");
		}
		if (!restricted && c.custodyRestricted() != null) {
			note = null;
		}
		boolean primary = (c.primary() != null) ? c.primary() : link.primary();
		if (restricted && primary) {
			if (Boolean.TRUE.equals(c.primary())) {
				throw ApiException.conflict("guardian_link.restricted_primary");
			}
			primary = false;
		}
		if (primary != link.primary()) {
			fields.add("primary");
		}
		if (primary && !link.primary()) {
			this.jdbc.sql("""
					update student_guardians set is_primary = false, updated_at = now(), version = version + 1
					where student_id = :st and id <> :id and is_primary""")
				.param("st", link.studentId())
				.param("id", linkId)
				.update();
		}
		RelationshipType relationship = (c.relationshipType() != null) ? c.relationshipType() : link.relationshipType();
		if (relationship != link.relationshipType()) {
			fields.add("relationshipType");
		}
		LinkPermissions before = link.permissions();
		LinkPermissions p = (c.permissions() != null) ? c.permissions().over(before) : before;
		if (!p.equals(before)) {
			fields.add("permissions");
		}
		this.jdbc.sql("""
				update student_guardians set relationship_type = :rel, is_primary = :primary, custody_restricted = :restricted,
				       restriction_note = :note, can_view_academics = :academics, can_message_staff = :message,
				       can_receive_notifications = :notify, can_award_points = :points, can_give_consent = :consent,
				       can_book_and_pay = :pay, updated_at = :now, version = version + 1
				where id = :id""")
			.param("rel", relationship.name())
			.param("primary", primary)
			.param("restricted", restricted)
			.param("note", note)
			.param("academics", p.canViewAcademics())
			.param("message", p.canMessageStaff())
			.param("notify", p.canReceiveNotifications())
			.param("points", p.canAwardPoints())
			.param("consent", p.canGiveConsent())
			.param("pay", p.canBookAndPay())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", linkId)
			.update();
		if (!fields.isEmpty()) {
			this.events.publishEvent(new GuardianLinkChanged(linkId, link.studentId(), link.guardianId(), schoolId,
					Change.UPDATED, user.userId()));
			AuditEntry entry = AuditEntry.of("guardian_link.update", AuditOutcome.SUCCESS)
				.target("guardian_link", linkId)
				.school(schoolId)
				.metadata("studentId", link.studentId().toString())
				.metadata("fields", fields)
				.metadata("custodyRestricted", restricted);
			if (fields.contains("permissions")) {
				entry.changes(before, p);
			}
			this.audit.record(entry);
		}
		return load(linkId, true);
	}

	public GuardianLinkView revoke(CurrentUser user, UUID schoolId, UUID linkId) {
		LinkRow link = lockManaged(user, schoolId, linkId);
		if (link.status() == LinkStatus.REVOKED || link.status() == LinkStatus.REJECTED) {
			return load(linkId, true);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update student_guardians set status = 'REVOKED', is_primary = false, revoked_at = :now, revoked_by = :by,
				       updated_at = :now, version = version + 1
				where id = :id""")
			.param("now", Timestamp.from(now))
			.param("by", user.userId())
			.param("id", linkId)
			.update();
		this.events.publishEvent(new GuardianLinkChanged(linkId, link.studentId(), link.guardianId(), schoolId,
				Change.REVOKED, user.userId()));
		this.audit.record(AuditEntry.of("guardian_link.revoke", AuditOutcome.SUCCESS)
			.target("guardian_link", linkId)
			.school(schoolId)
			.metadata("studentId", link.studentId().toString())
			.metadata("previousStatus", link.status().name()));
		return load(linkId, true);
	}

	/**
	 * A link created by the school ({@code school.guardians.manage}): the administrator vouches for the adult, as for
	 * staff provisioning. The adult is found by phone or email or created (ACTIVE, destination verified); the link is
	 * ACTIVE with {@code SCHOOL_VERIFIED}. A PENDING link of the same adult is approved instead; an ACTIVE one is
	 * returned unchanged.
	 */
	public GuardianLinkView create(CurrentUser user, UUID schoolId, UUID studentId, NewGuardian r) {
		this.rules.require(user, schoolId, studentId, this.rules.manages(user, schoolId));
		SchoolRef school = this.directory.school(schoolId).orElseThrow(() -> ApiException.notFound("school.not_found"));
		ProvisionedUser adult = this.accounts.findOrCreateVerified(new ProvisionRequest(r.phone(), r.email(),
				(r.country() != null) ? r.country() : school.countryCode(), r.name().trim(), null, r.name().trim(),
				school.defaultLanguageCode()));
		if (adult.id().equals(studentId) || isManaged(adult.id())) {
			throw ApiException.conflict("guardian.account_not_eligible");
		}
		ensureProfile(adult.id());
		Optional<UUID> existing = liveLinkId(studentId, adult.id());
		UUID linkId;
		Instant now = this.clock.instant();
		if (existing.isPresent()) {
			linkId = existing.get();
			LinkRow link = find(linkId).orElseThrow();
			if (link.status() == LinkStatus.PENDING) {
				this.jdbc.sql("""
						update student_guardians set status = 'ACTIVE', verification_method = 'SCHOOL_VERIFIED',
						       verified_by_school_id = :school, verified_by = :by, verified_at = :now, decided_by = :by,
						       decided_at = :now, updated_at = :now, version = version + 1 where id = :id""")
					.param("school", schoolId)
					.param("by", user.userId())
					.param("now", Timestamp.from(now))
					.param("id", linkId)
					.update();
				this.events.publishEvent(new GuardianLinkChanged(linkId, studentId, adult.id(), schoolId,
						Change.ACTIVATED, user.userId()));
			}
		}
		else {
			linkId = Ids.newId();
			this.jdbc.sql("""
					insert into student_guardians (id, student_id, guardian_id, relationship_type, status, requested_via,
					                               verification_method, verified_by_school_id, verified_by, verified_at,
					                               decided_by, decided_at, created_at, updated_at)
					values (:id, :st, :g, :rel, 'ACTIVE', 'ADMIN', 'SCHOOL_VERIFIED', :school, :by, :now, :by, :now, :now, :now)""")
				.param("id", linkId)
				.param("st", studentId)
				.param("g", adult.id())
				.param("rel", r.relationshipType().name())
				.param("school", schoolId)
				.param("by", user.userId())
				.param("now", Timestamp.from(now))
				.update();
			this.events.publishEvent(new GuardianLinkChanged(linkId, studentId, adult.id(), schoolId, Change.ACTIVATED,
					user.userId()));
		}
		this.audit.record(AuditEntry.of("guardian_link.create", AuditOutcome.SUCCESS)
			.target("guardian_link", linkId)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("guardianId", adult.id().toString())
			.metadata("relationshipType", r.relationshipType().name())
			.metadata("accountCreated", adult.created())
			.metadata("destination", (adult.phoneE164() != null) ? Masking.phone(adult.phoneE164())
					: Masking.email(adult.email())));
		if (adult.created()) {
			welcome(adult, school);
		}
		return load(linkId, true);
	}

	// --- helpers --------------------------------------------------------------------------

	void ensureProfile(UUID userId) {
		this.jdbc.sql("insert into guardian_profiles (user_id) values (:u) on conflict do nothing").param("u", userId).update();
	}

	Optional<UUID> liveLinkId(UUID studentId, UUID guardianId) {
		return this.jdbc.sql("""
				select id from student_guardians where student_id = :st and guardian_id = :g and status in ('PENDING', 'ACTIVE')""")
			.param("st", studentId)
			.param("g", guardianId)
			.query(UUID.class)
			.optional();
	}

	Optional<LinkRow> find(UUID linkId) {
		return this.jdbc.sql(SELECT + " where sg.id = :id").param("id", linkId).query(GuardianLinkService::row).optional();
	}

	GuardianLinkView load(UUID linkId, boolean manager) {
		return find(linkId).map((r) -> view(r, manager)).orElseThrow(() -> ApiException.notFound("guardian_link.not_found"));
	}

	private boolean isManaged(UUID userId) {
		return this.jdbc.sql("select account_type = 'MANAGED' from users where id = :id")
			.param("id", userId)
			.query(Boolean.class)
			.single();
	}

	/** The link of an ACTIVE student of the school, locked; 404 unless the caller may read it, 403 unless they verify. */
	private LinkRow lockForVerification(CurrentUser user, UUID schoolId, UUID linkId) {
		LinkRow link = lockInSchool(user, schoolId, linkId);
		if (!this.rules.mayVerify(user, schoolId, link.studentId())) {
			throw ApiException.forbidden("guardian.access_denied");
		}
		if (link.status() != LinkStatus.PENDING) {
			throw ApiException.conflict("guardian_link.not_pending");
		}
		return link;
	}

	private LinkRow lockManaged(CurrentUser user, UUID schoolId, UUID linkId) {
		LinkRow link = lockInSchool(user, schoolId, linkId);
		if (!this.rules.manages(user, schoolId)) {
			throw ApiException.forbidden("guardian.access_denied");
		}
		return link;
	}

	/** 404 {@code guardian_link.not_found} for links of other schools' students and for callers who cannot read them. */
	private LinkRow lockInSchool(CurrentUser user, UUID schoolId, UUID linkId) {
		boolean manager = this.rules.manages(user, schoolId);
		if (!manager) {
			this.gate.requireVisible(user, schoolId);
		}
		UUID studentId = this.jdbc.sql("select student_id from student_guardians where id = :id for update")
			.param("id", linkId)
			.query(UUID.class)
			.optional()
			.filter((st) -> this.rules.activeStudent(schoolId, st))
			.filter((st) -> manager || this.rules.mayReadLinks(user, schoolId, st))
			.orElseThrow(() -> ApiException.notFound("guardian_link.not_found"));
		return find(linkId).filter((r) -> r.studentId().equals(studentId)).orElseThrow();
	}

	private void welcome(ProvisionedUser adult, SchoolRef school) {
		java.util.Locale locale = java.util.Locale.forLanguageTag(
				(adult.preferredLanguageCode() != null) ? adult.preferredLanguageCode() : school.defaultLanguageCode());
		String phone = adult.phoneE164();
		String mail = adult.email();
		AfterCommit.run(() -> this.executor.execute(() -> {
			try {
				if (phone != null) {
					this.sms.send(phone, this.messages.getMessage("guardian.welcome.sms", new Object[] { school.name() }, locale));
				}
				else if (mail != null) {
					this.email.send(mail,
							this.messages.getMessage("guardian.welcome.email.subject", new Object[] { school.name() }, locale),
							this.messages.getMessage("guardian.welcome.email.body", new Object[] { school.name() }, locale));
				}
			}
			catch (RuntimeException ex) {
				log.error("Guardian welcome message could not be delivered: {}", ex.getMessage());
			}
		}));
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	static final String SELECT = """
			select sg.id, sg.student_id, sg.guardian_id, sg.relationship_type, sg.is_primary, sg.status, sg.requested_via,
			       sg.invitation_id, sg.verification_method, sg.verified_at, sg.decided_at, sg.decision_note,
			       sg.can_view_academics, sg.can_message_staff, sg.can_receive_notifications, sg.can_award_points,
			       sg.can_give_consent, sg.can_book_and_pay, sg.custody_restricted, sg.restriction_note, sg.created_at,
			       su.display_name as student_name, gu.display_name as guardian_name, gu.phone_e164, gu.email,
			       i.guardian_name as invited_name,
			       case when i.id is null or i.channel not in ('SMS', 'EMAIL') then null
			            else coalesce((i.destination_e164 is not null and i.destination_e164 = gu.phone_e164
			                           and gu.phone_verified_at is not null), false)
			              or coalesce((i.destination_email is not null and i.destination_email = gu.email
			                           and gu.email_verified_at is not null), false) end as contact_matches
			from student_guardians sg
			join users su on su.id = sg.student_id
			join users gu on gu.id = sg.guardian_id
			left join invitations i on i.id = sg.invitation_id""";

	static LinkRow row(ResultSet rs, int i) throws SQLException {
		Object matches = rs.getObject("contact_matches");
		String method = rs.getString("verification_method");
		return new LinkRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "student_id"), Rows.uuid(rs, "guardian_id"),
				RelationshipType.valueOf(rs.getString("relationship_type")), rs.getBoolean("is_primary"),
				LinkStatus.valueOf(rs.getString("status")), RequestedVia.valueOf(rs.getString("requested_via")),
				Rows.uuid(rs, "invitation_id"), (method != null) ? VerificationMethod.valueOf(method) : null,
				Rows.instant(rs, "verified_at"), Rows.instant(rs, "decided_at"), rs.getString("decision_note"),
				new LinkPermissions(rs.getBoolean("can_view_academics"), rs.getBoolean("can_message_staff"),
						rs.getBoolean("can_receive_notifications"), rs.getBoolean("can_award_points"),
						rs.getBoolean("can_give_consent"), rs.getBoolean("can_book_and_pay")),
				rs.getBoolean("custody_restricted"), rs.getString("restriction_note"), Rows.instant(rs, "created_at"),
				rs.getString("student_name"), rs.getString("guardian_name"), rs.getString("phone_e164"),
				rs.getString("email"), rs.getString("invited_name"), (matches != null) ? (Boolean) matches : null);
	}

	/** Contact details, notes and the invitation comparison only for {@code school.guardians.manage}. */
	static GuardianLinkView view(LinkRow r, boolean manager) {
		return new GuardianLinkView(r.id(), r.studentId(), r.studentName(), r.guardianId(), r.guardianName(),
				r.relationshipType(), r.primary(), r.status(), r.requestedVia(), r.verificationMethod(), r.verifiedAt(),
				r.createdAt(), r.permissions(), r.custodyRestricted(), manager ? r.restrictionNote() : null,
				manager ? Masking.phone(r.phone()) : null, manager ? Masking.email(r.email()) : null,
				manager ? r.invitedName() : null, manager ? r.contactMatchesInvitation() : null,
				manager ? r.decisionNote() : null, r.decidedAt());
	}

	record LinkRow(UUID id, UUID studentId, UUID guardianId, RelationshipType relationshipType, boolean primary,
			LinkStatus status, RequestedVia requestedVia, UUID invitationId, VerificationMethod verificationMethod,
			Instant verifiedAt, Instant decidedAt, String decisionNote, LinkPermissions permissions,
			boolean custodyRestricted, String restrictionNote, Instant createdAt, String studentName,
			String guardianName, String phone, String email, String invitedName, Boolean contactMatchesInvitation) {
	}

	public enum LinkStatus {

		PENDING, ACTIVE, REJECTED, REVOKED

	}

	public enum RequestedVia {

		INVITE_LINK, PARENT_CODE, ADMIN

	}

	public enum VerificationMethod {

		SCHOOL_INVITE, SCHOOL_VERIFIED, DOCUMENT, PLATFORM_ADMIN

	}

	/** The permission flags of a link (R-29); a custody restriction overrides all of them. */
	public record LinkPermissions(@NotNull boolean canViewAcademics, @NotNull boolean canMessageStaff,
			@NotNull boolean canReceiveNotifications, @NotNull boolean canAwardPoints, @NotNull boolean canGiveConsent,
			@NotNull boolean canBookAndPay) {

		boolean allows(GuardianPermission permission) {
			return switch (permission) {
				case VIEW_ACADEMICS -> this.canViewAcademics;
				case MESSAGE_STAFF -> this.canMessageStaff;
				case RECEIVE_NOTIFICATIONS -> this.canReceiveNotifications;
				case AWARD_POINTS -> this.canAwardPoints;
				case GIVE_CONSENT -> this.canGiveConsent;
				case BOOK_AND_PAY -> this.canBookAndPay;
			};
		}

	}

	/** Absent fields stay unchanged. */
	public record PermissionChange(Boolean canViewAcademics, Boolean canMessageStaff, Boolean canReceiveNotifications,
			Boolean canAwardPoints, Boolean canGiveConsent, Boolean canBookAndPay) {

		LinkPermissions over(LinkPermissions p) {
			return new LinkPermissions(or(this.canViewAcademics, p.canViewAcademics()),
					or(this.canMessageStaff, p.canMessageStaff()),
					or(this.canReceiveNotifications, p.canReceiveNotifications()),
					or(this.canAwardPoints, p.canAwardPoints()), or(this.canGiveConsent, p.canGiveConsent()),
					or(this.canBookAndPay, p.canBookAndPay()));
		}

		private static boolean or(Boolean value, boolean fallback) {
			return (value != null) ? value : fallback;
		}

	}

	public record LinkChange(RelationshipType relationshipType, Boolean primary, PermissionChange permissions,
			Boolean custodyRestricted, String restrictionNote) {

		@Override
		public String toString() {
			return "LinkChange[redacted]";
		}

	}

	public record NewGuardian(String phone, String email, String country, String name,
			RelationshipType relationshipType) {

		@Override
		public String toString() {
			return "NewGuardian[redacted]";
		}

	}

	/**
	 * A guardian link as school staff see it. Contact details (masked), the inviter's name hint, whether the accepting
	 * adult proved the invited destination, and notes only for holders of {@code school.guardians.manage}.
	 */
	public record GuardianLinkView(@NotNull UUID id, @NotNull UUID studentId, @NotNull String studentName,
			@NotNull UUID guardianId, @NotNull String guardianName, @NotNull RelationshipType relationshipType,
			@NotNull boolean primary, @NotNull LinkStatus status, @NotNull RequestedVia requestedVia,
			VerificationMethod verificationMethod, Instant verifiedAt, @NotNull Instant createdAt,
			@NotNull LinkPermissions permissions,
			@NotNull @Schema(description = "Grants nothing at all while true (court order etc.)") boolean custodyRestricted,
			@Schema(description = "school.guardians.manage only") String restrictionNote,
			@Schema(description = "Masked; school.guardians.manage only") String guardianPhone,
			@Schema(description = "Masked; school.guardians.manage only") String guardianEmail,
			@Schema(description = "The name the inviter typed; school.guardians.manage only") String invitedName,
			@Schema(description = "SMS/email invitations: whether the adult proved the invited phone or email; school.guardians.manage only") Boolean contactMatchesInvitation,
			@Schema(description = "The verifier's note; school.guardians.manage only") String decisionNote,
			Instant decidedAt) {
	}

}
