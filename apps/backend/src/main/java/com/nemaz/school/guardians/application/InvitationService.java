package com.nemaz.school.guardians.application;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.common.web.ClientRequests;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.guardians.application.GuardianLinkService.GuardianLinkView;
import com.nemaz.school.guardians.application.GuardianLinkService.RequestedVia;
import com.nemaz.school.identity.CodeHasher;
import com.nemaz.school.identity.Contacts;
import com.nemaz.school.identity.InvitationRedeemer;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.VerifiedContacts;
import com.nemaz.school.system.EmailSender;
import com.nemaz.school.system.SmsSender;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.SchoolRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guardian invitations (spec §125, R-29): an SMS or email invitation with a link, or printed parent codes for a class,
 * and their redemption.
 * <ul>
 * <li><b>Who invites:</b> see {@link GuardianRules}. At most {@value #MAX_LIVE} live invitations per student.</li>
 * <li><b>SMS / email:</b> single use, {@code school.guardians.invite-ttl} (14 days), a 256-bit link token of which only
 * the SHA-256 is stored, sent in the school's default language. The message names the school, not the child.</li>
 * <li><b>Parent codes:</b> one per student, {@code guardians.parent_code.max_uses} adults (default 4) within
 * {@code guardians.parent_code.ttl_days} (default 30); reissuing revokes the student's previous live code. The plaintext
 * is in the issue response only (never stored, logged, audited or put in events; not {@code @Idempotent} for the same
 * reason).</li>
 * <li><b>Preview and redeem:</b> unknown, expired, revoked and used-up invitations, students no longer ACTIVE in the
 * school and schools that are not ACTIVE all look the same (404 {@code invitation.not_found}); guessing is rate limited
 * ({@link InvitationRateLimits}). The preview shows the school, the child's first name, the relationship and the
 * channel: nothing else about the child.</li>
 * <li><b>Link status:</b> PENDING, unless the invitation was sent by SMS or email, the school has
 * {@code guardians.auto_verify_invite} on, and the accepting adult has verified exactly the invited destination (then
 * ACTIVE with {@code SCHOOL_INVITE}). Printed codes always give PENDING links. Accepting twice returns the same link
 * and uses nothing up.</li>
 * </ul>
 */
@Service
@Transactional
public class InvitationService implements InvitationRedeemer {

	static final int MAX_LIVE = 10;

	static final int DEFAULT_CODE_TTL_DAYS = 30;

	static final int DEFAULT_CODE_MAX_USES = 4;

	private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

	private static final String LIVE = """
			i.invitation_type = 'GUARDIAN_LINK' and i.status in ('PENDING', 'SENT') and i.revoked_at is null
			and i.expires_at > :now and i.use_count < i.max_uses""";

	private final SecureRandom random = new SecureRandom();

	private final JdbcClient jdbc;

	private final GuardianRules rules;

	private final GuardianLinkService links;

	private final ClassAccess classAccess;

	private final ClassMemberships memberships;

	private final CodeHasher hasher;

	private final UserDirectory users;

	private final SettingsService settings;

	private final TenantDirectory directory;

	private final InvitationRateLimits limits;

	private final GuardiansProperties props;

	private final SmsSender sms;

	private final EmailSender email;

	private final MessageSource messages;

	private final TaskExecutor executor;

	private final AuditLog audit;

	private final Clock clock;

	InvitationService(JdbcClient jdbc, GuardianRules rules, GuardianLinkService links, ClassAccess classAccess,
			ClassMemberships memberships, CodeHasher hasher, UserDirectory users, SettingsService settings,
			TenantDirectory directory, InvitationRateLimits limits, GuardiansProperties props, SmsSender sms,
			EmailSender email, MessageSource messages, @Qualifier("applicationTaskExecutor") TaskExecutor executor,
			AuditLog audit, Clock clock) {
		this.jdbc = jdbc;
		this.rules = rules;
		this.links = links;
		this.classAccess = classAccess;
		this.memberships = memberships;
		this.hasher = hasher;
		this.users = users;
		this.settings = settings;
		this.directory = directory;
		this.limits = limits;
		this.props = props;
		this.sms = sms;
		this.email = email;
		this.messages = messages;
		this.executor = executor;
		this.audit = audit;
		this.clock = clock;
	}

	// --- SMS and email invitations ------------------------------------------------------------

	public InvitationView invite(CurrentUser actor, UUID schoolId, UUID studentId, NewInvitation r) {
		this.rules.require(actor, schoolId, studentId, this.rules.mayInvite(actor, schoolId, studentId));
		SchoolRef school = this.directory.school(schoolId).orElseThrow(() -> ApiException.notFound("school.not_found"));
		boolean hasPhone = r.phone() != null && !r.phone().isBlank();
		boolean hasEmail = r.email() != null && !r.email().isBlank();
		if (hasPhone == hasEmail) {
			throw ApiException.badRequest("invitation.destination_required");
		}
		String phone = hasPhone ? Contacts.phone(r.phone(), (r.country() != null) ? r.country() : school.countryCode())
			.orElseThrow(() -> ApiException.badRequest("user.invalid_phone")) : null;
		String mail = hasEmail ? Contacts.email(r.email()).orElseThrow(() -> ApiException.badRequest("user.invalid_email"))
				: null;
		lockStudent(studentId);
		Instant now = this.clock.instant();
		requireBelowLimit(studentId, now);
		String token = InvitationKeys.newToken(this.random);
		UUID id = Ids.newId();
		Instant expiresAt = now.plus(this.props.inviteTtl());
		this.jdbc.sql("""
				insert into invitations (id, invitation_type, school_id, student_id, relationship_type, guardian_name, channel,
				                         destination_e164, destination_email, token_hash, status, max_uses, invited_by,
				                         expires_at, created_at)
				values (:id, 'GUARDIAN_LINK', :s, :st, :rel, :name, :channel, :phone, :email, :hash, 'SENT', 1, :by, :exp, :now)""")
			.param("id", id)
			.param("s", schoolId)
			.param("st", studentId)
			.param("rel", r.relationshipType().name())
			.param("name", blankToNull(r.guardianName()))
			.param("channel", hasPhone ? "SMS" : "EMAIL")
			.param("phone", phone)
			.param("email", mail)
			.param("hash", InvitationKeys.sha256Hex(token))
			.param("by", actor.userId())
			.param("exp", Timestamp.from(expiresAt))
			.param("now", Timestamp.from(now))
			.update();
		this.audit.record(AuditEntry.of("guardian_invitation.create", AuditOutcome.SUCCESS)
			.target("invitation", id)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("channel", hasPhone ? "SMS" : "EMAIL")
			.metadata("destination", hasPhone ? Masking.phone(phone) : Masking.email(mail))
			.metadata("relationshipType", r.relationshipType().name()));
		String language = (school.defaultLanguageCode() != null) ? school.defaultLanguageCode() : "en";
		String link = this.props.inviteBaseUrl() + "/" + language + "/invite/" + token;
		long days = Math.max(1, this.props.inviteTtl().toDays());
		Locale locale = Locale.forLanguageTag(language);
		AfterCommit.run(() -> this.executor.execute(() -> send(phone, mail, school.name(), link, days, locale)));
		return view(id);
	}

	// --- printed parent codes -------------------------------------------------------------------

	/**
	 * New parent codes for students of a class (default: every ACTIVE student); each student's previous live code is
	 * revoked. The codes are in this answer only.
	 */
	public List<IssuedParentCode> issueCodes(CurrentUser actor, UUID schoolId, UUID classId, List<UUID> studentIds) {
		ClassRef ref = this.classAccess.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.classAccess.canView(actor, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		if (!this.rules.mayInviteForClass(actor, schoolId, classId)) {
			throw ApiException.forbidden("guardian.access_denied");
		}
		Set<UUID> inClass = this.memberships.activeStudentIds(classId);
		Set<UUID> students = (studentIds == null || studentIds.isEmpty()) ? inClass : new LinkedHashSet<>(studentIds);
		if (!inClass.containsAll(students)) {
			throw ApiException.conflict("invitation.student_not_in_class");
		}
		SettingScope scope = SettingScope.school(schoolId);
		int ttlDays = this.settings.getInt("guardians.parent_code.ttl_days", scope).orElse(DEFAULT_CODE_TTL_DAYS);
		int maxUses = this.settings.getInt("guardians.parent_code.max_uses", scope).orElse(DEFAULT_CODE_MAX_USES);
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(Duration.ofDays(ttlDays));
		Map<UUID, String> names = givenNames(students);
		List<IssuedParentCode> issued = new ArrayList<>();
		for (UUID studentId : students.stream().sorted().toList()) {
			lockStudent(studentId);
			revokeLiveCodes(schoolId, studentId, actor.userId(), now);
			requireBelowLimit(studentId, now);
			String code = InvitationKeys.newCode(this.random);
			UUID id = Ids.newId();
			this.jdbc.sql("""
					insert into invitations (id, invitation_type, school_id, class_id, student_id, channel, code_hash, status,
					                         max_uses, invited_by, expires_at, created_at)
					values (:id, 'GUARDIAN_LINK', :s, :c, :st, 'PRINTED_CODE', :hash, 'PENDING', :max, :by, :exp, :now)""")
				.param("id", id)
				.param("s", schoolId)
				.param("c", classId)
				.param("st", studentId)
				.param("hash", this.hasher.hmac(InvitationKeys.CODE_PURPOSE, code))
				.param("max", maxUses)
				.param("by", actor.userId())
				.param("exp", Timestamp.from(expiresAt))
				.param("now", Timestamp.from(now))
				.update();
			this.audit.record(AuditEntry.of("guardian_invitation.issue_code", AuditOutcome.SUCCESS)
				.target("invitation", id)
				.school(schoolId)
				.metadata("studentId", studentId.toString())
				.metadata("classId", classId.toString())
				.metadata("maxUses", maxUses)
				.metadata("expiresAt", expiresAt.toString()));
			issued.add(new IssuedParentCode(id, studentId, names.getOrDefault(studentId, ""), InvitationKeys.display(code),
					expiresAt, maxUses));
		}
		issued.sort(Comparator.comparing((IssuedParentCode c) -> c.childFirstName().toLowerCase(Locale.ROOT))
			.thenComparing(IssuedParentCode::studentId));
		return issued;
	}

	// --- reading and revoking -------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<InvitationView> forStudent(CurrentUser actor, UUID schoolId, UUID studentId) {
		this.rules.require(actor, schoolId, studentId, this.rules.mayInvite(actor, schoolId, studentId));
		return this.jdbc.sql(SELECT + " where i.school_id = :s and i.student_id = :st and i.invitation_type = 'GUARDIAN_LINK'"
				+ " order by i.created_at desc, i.id desc limit 100")
			.param("s", schoolId)
			.param("st", studentId)
			.query(this::invitation)
			.list();
	}

	public InvitationView revoke(CurrentUser actor, UUID schoolId, UUID invitationId) {
		UUID studentId = this.jdbc.sql("""
				select student_id from invitations where id = :id and school_id = :s and invitation_type = 'GUARDIAN_LINK'
				for update""")
			.param("id", invitationId)
			.param("s", schoolId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (studentId == null) {
			if (!this.rules.manages(actor, schoolId)) {
				this.rules.requireVisible(actor, schoolId);
			}
			throw ApiException.notFound("invitation.not_found");
		}
		this.rules.require(actor, schoolId, studentId, this.rules.mayInvite(actor, schoolId, studentId));
		int changed = this.jdbc.sql("""
				update invitations set status = 'REVOKED', revoked_at = :now, revoked_by = :by
				where id = :id and status in ('PENDING', 'SENT')""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("by", actor.userId())
			.param("id", invitationId)
			.update();
		if (changed > 0) {
			this.audit.record(AuditEntry.of("guardian_invitation.revoke", AuditOutcome.SUCCESS)
				.target("invitation", invitationId)
				.school(schoolId)
				.metadata("studentId", studentId.toString()));
		}
		return view(invitationId);
	}

	// --- preview and redemption -------------------------------------------------------------------

	@Transactional(readOnly = true)
	public InvitationPreview preview(InvitationKey key) {
		Live live = findLive(key, false).orElseThrow(() -> ApiException.notFound("invitation.not_found"));
		SchoolRef school = this.directory.school(live.schoolId()).orElseThrow();
		String firstName = this.jdbc.sql("select given_name from users where id = :id")
			.param("id", live.studentId())
			.query(String.class)
			.single();
		return new InvitationPreview(school.name(), firstName, live.relationshipType(),
				InvitationChannel.valueOf(live.channel()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<InvitationTarget> find(InvitationKey key) {
		return findLive(key, false).map((live) -> new InvitationTarget(live.id(), live.schoolId(), live.guardianName(),
				this.directory.school(live.schoolId()).map(SchoolRef::defaultLanguageCode).orElse(null)));
	}

	@Override
	public Optional<UUID> redeem(InvitationKey key, UUID userId, VerifiedContact proof) {
		return findLive(key, true).map((live) -> accept(live, userId, proof.phoneE164(), proof.email(), null));
	}

	/** A signed-in adult redeems a token or code; their own verified phone or email is the proof. */
	public GuardianLinkView redeemSignedIn(CurrentUser user, InvitationKey key, RelationshipType relationship) {
		VerifiedContacts contacts = this.users.verifiedContacts(user.userId()).orElse(null);
		if (contacts == null || !contacts.activeStandardAccount()
				|| (contacts.phoneE164() == null && contacts.email() == null)) {
			throw ApiException.forbidden("guardian.account_not_eligible");
		}
		Live live = findLive(key, true).orElseThrow(() -> ApiException.notFound("invitation.not_found"));
		if (live.studentId().equals(user.userId())) {
			throw ApiException.forbidden("guardian.account_not_eligible");
		}
		UUID linkId = accept(live, user.userId(), contacts.phoneE164(), contacts.email(), relationship);
		return this.links.load(linkId, false);
	}

	/**
	 * Both redemption paths (one-time code, signed in) end here, so the eligibility rules hold for both: the adult must
	 * have an ACTIVE STANDARD account (never a school-managed student account) and cannot be the child.
	 */
	private UUID accept(Live live, UUID userId, String provenPhone, String provenEmail, RelationshipType requested) {
		boolean eligible = !live.studentId().equals(userId) && this.users.verifiedContacts(userId)
			.map(VerifiedContacts::activeStandardAccount)
			.orElse(false);
		if (!eligible) {
			throw ApiException.forbidden("guardian.account_not_eligible");
		}
		Optional<UUID> existing = this.links.liveLinkId(live.studentId(), userId);
		if (existing.isPresent()) {
			return existing.get();
		}
		boolean destinationProven = (live.destinationE164() != null && live.destinationE164().equals(provenPhone))
				|| (live.destinationEmail() != null && live.destinationEmail().equalsIgnoreCase(provenEmail));
		boolean autoVerify = ("SMS".equals(live.channel()) || "EMAIL".equals(live.channel())) && destinationProven
				&& this.rules.autoVerifies(live.schoolId());
		RelationshipType relationship = (live.relationshipType() != null) ? live.relationshipType()
				: (requested != null) ? requested : RelationshipType.OTHER;
		RequestedVia via = "PRINTED_CODE".equals(live.channel()) ? RequestedVia.PARENT_CODE : RequestedVia.INVITE_LINK;
		UUID linkId = this.links.request(live.studentId(), userId, relationship, via, live.id(), live.schoolId(),
				autoVerify ? live.invitedBy() : null);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update invitations set use_count = use_count + 1, accepted_by = :u, accepted_at = :now,
				       status = case when use_count + 1 >= max_uses then 'ACCEPTED' else status end
				where id = :id""")
			.param("u", userId)
			.param("now", Timestamp.from(now))
			.param("id", live.id())
			.update();
		return linkId;
	}

	/**
	 * The live invitation of a token or code (with the guessing limits), or empty. {@code lock} takes a row lock for
	 * redemption. The student must still be ACTIVE in the school and the school ACTIVE.
	 */
	private Optional<Live> findLive(InvitationKey key, boolean lock) {
		String ip = ClientRequests.ip();
		boolean byCode = key.code() != null && !key.code().isBlank();
		String prefix = byCode ? InvitationKeys.prefix(key.code()) : null;
		this.limits.attempt(ip, prefix);
		Optional<String> hash = byCode
				? InvitationKeys.code(key.code()).map((c) -> this.hasher.hmac(InvitationKeys.CODE_PURPOSE, c))
				: InvitationKeys.token(key.token()).map(InvitationKeys::sha256Hex);
		Optional<Live> live = hash.flatMap((h) -> this.jdbc.sql("select i.id, i.school_id, i.student_id, i.channel,"
				+ " i.relationship_type, i.guardian_name, i.destination_e164, i.destination_email::text as destination_email,"
				+ " i.invited_by from invitations i where " + (byCode ? "i.code_hash" : "i.token_hash") + " = :h and "
				+ LIVE + " " + """
						and exists (select 1 from school_students ss where ss.school_id = i.school_id
						             and ss.student_id = i.student_id and ss.status = 'ACTIVE')
						 and exists (select 1 from schools s where s.id = i.school_id and s.status in ('ONBOARDING', 'ACTIVE'))"""
				+ (lock ? " for update of i" : ""))
			.param("h", h)
			.param("now", Timestamp.from(this.clock.instant()))
			.query(InvitationService::live)
			.optional());
		if (live.isEmpty()) {
			this.limits.failed(ip, prefix);
		}
		return live;
	}

	// --- helpers ------------------------------------------------------------------------------

	private void lockStudent(UUID studentId) {
		this.jdbc.sql("select user_id from student_profiles where user_id = :st for update")
			.param("st", studentId)
			.query(UUID.class)
			.optional();
	}

	private void requireBelowLimit(UUID studentId, Instant now) {
		int live = this.jdbc.sql("select count(*) from invitations i where i.student_id = :st and " + LIVE)
			.param("st", studentId)
			.param("now", Timestamp.from(now))
			.query(Integer.class)
			.single();
		if (live >= MAX_LIVE) {
			throw ApiException.conflict("invitation.limit_reached", MAX_LIVE);
		}
	}

	private void revokeLiveCodes(UUID schoolId, UUID studentId, UUID actorId, Instant now) {
		List<UUID> revoked = this.jdbc.sql("""
				update invitations set status = 'REVOKED', revoked_at = :now, revoked_by = :by
				where school_id = :s and student_id = :st and channel = 'PRINTED_CODE' and status in ('PENDING', 'SENT')
				returning id""")
			.param("now", Timestamp.from(now))
			.param("by", actorId)
			.param("s", schoolId)
			.param("st", studentId)
			.query(UUID.class)
			.list();
		for (UUID id : revoked) {
			this.audit.record(AuditEntry.of("guardian_invitation.revoke", AuditOutcome.SUCCESS)
				.target("invitation", id)
				.school(schoolId)
				.metadata("studentId", studentId.toString())
				.metadata("reason", "REPLACED"));
		}
	}

	private Map<UUID, String> givenNames(Set<UUID> ids) {
		Map<UUID, String> names = new java.util.HashMap<>();
		if (ids.isEmpty()) {
			return names;
		}
		this.jdbc.sql("select id, given_name from users where id in (:ids)")
			.param("ids", ids)
			.query((rs, i) -> names.put(Rows.uuid(rs, "id"), rs.getString("given_name")))
			.list();
		return names;
	}

	private void send(String phone, String mail, String school, String link, long days, Locale locale) {
		try {
			if (phone != null) {
				this.sms.send(phone, this.messages.getMessage("guardian.invite.sms", new Object[] { school, link, days }, locale));
			}
			else {
				this.email.send(mail, this.messages.getMessage("guardian.invite.email.subject", new Object[] { school }, locale),
						this.messages.getMessage("guardian.invite.email.body", new Object[] { school, link, days }, locale));
			}
		}
		catch (RuntimeException ex) {
			log.error("Guardian invitation could not be delivered: {}", ex.getMessage());
		}
	}

	private InvitationView view(UUID id) {
		return this.jdbc.sql(SELECT + " where i.id = :id").param("id", id).query(this::invitation).single();
	}

	private static final String SELECT = """
			select i.id, i.student_id, i.class_id, i.channel, i.relationship_type, i.guardian_name, i.destination_e164,
			       i.destination_email::text as destination_email, i.status, i.max_uses, i.use_count, i.expires_at,
			       i.created_at, i.revoked_at
			from invitations i""";

	private InvitationView invitation(ResultSet rs, int i) throws SQLException {
		Instant expiresAt = Rows.instant(rs, "expires_at");
		String status = rs.getString("status");
		boolean expired = ("PENDING".equals(status) || "SENT".equals(status)) && !expiresAt.isAfter(this.clock.instant());
		String rel = rs.getString("relationship_type");
		String phone = rs.getString("destination_e164");
		String mail = rs.getString("destination_email");
		return new InvitationView(Rows.uuid(rs, "id"), Rows.uuid(rs, "student_id"), Rows.uuid(rs, "class_id"),
				InvitationChannel.valueOf(rs.getString("channel")), (rel != null) ? RelationshipType.valueOf(rel) : null,
				rs.getString("guardian_name"), (phone != null) ? Masking.phone(phone) : Masking.email(mail),
				expired ? InvitationStatus.EXPIRED : InvitationStatus.valueOf(status), rs.getInt("use_count"),
				rs.getInt("max_uses"), expiresAt, Rows.instant(rs, "created_at"));
	}

	private static Live live(ResultSet rs, int i) throws SQLException {
		String rel = rs.getString("relationship_type");
		return new Live(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "student_id"),
				rs.getString("channel"), (rel != null) ? RelationshipType.valueOf(rel) : null, rs.getString("guardian_name"),
				rs.getString("destination_e164"), rs.getString("destination_email"), Rows.uuid(rs, "invited_by"));
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private record Live(UUID id, UUID schoolId, UUID studentId, String channel, RelationshipType relationshipType,
			String guardianName, String destinationE164, String destinationEmail, UUID invitedBy) {
	}

	public enum InvitationChannel {

		SMS, EMAIL, LINK, PRINTED_CODE

	}

	public enum InvitationStatus {

		PENDING, SENT, ACCEPTED, EXPIRED, REVOKED

	}

	public record NewInvitation(String phone, String email, String country, RelationshipType relationshipType,
			String guardianName) {

		@Override
		public String toString() {
			return "NewInvitation[redacted]";
		}

	}

	/** An invitation as the school sees it: the destination masked, never the token or code. */
	public record InvitationView(@NotNull UUID id, @NotNull UUID studentId, UUID classId,
			@NotNull InvitationChannel channel, RelationshipType relationshipType,
			@Schema(description = "The name the inviter typed") String guardianName,
			@Schema(description = "Masked phone or email (SMS and EMAIL invitations)") String destination,
			@NotNull @Schema(description = "EXPIRED when the validity ended while still unused") InvitationStatus status,
			@NotNull int useCount, @NotNull int maxUses, @NotNull Instant expiresAt, @NotNull Instant createdAt) {
	}

	/** The only time a parent code is visible: {@code code} is {@code XXXXX-XXXXX}. */
	public record IssuedParentCode(@NotNull UUID invitationId, @NotNull UUID studentId, @NotNull String childFirstName,
			@NotNull String code, @NotNull Instant expiresAt, @NotNull int maxUses) {

		@Override
		public String toString() {
			return "IssuedParentCode[redacted]";
		}

	}

	/** What an invitation shows before anyone signs in: nothing about the child beyond the first name. */
	public record InvitationPreview(@NotNull String schoolName, @NotNull String childFirstName,
			RelationshipType relationshipType, @NotNull InvitationChannel channel) {
	}

}
