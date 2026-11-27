package com.nemaz.school.attendance.application;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMemberships.StudentClass;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.attendance.application.CheckinModels.AttendanceCredentialView;
import com.nemaz.school.attendance.application.CheckinModels.CredentialReason;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheet;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheetItem;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheetRequest;
import com.nemaz.school.attendance.application.CheckinModels.CredentialSheetSkip;
import com.nemaz.school.attendance.application.CheckinModels.IssuedAttendanceCredential;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.tx.AdvisoryLocks;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attendance credentials of students and staff (M7e): the static QR of a card. A credential is <b>not a login code</b>
 * (D6: a login code is a password, this is an identifier that can only record presence) and carries no personal data: 128
 * random bits ({@code SecureRandom}, base64url, 22 characters) printed as the bare token. Only its keyed hash is stored
 * ({@link AttendanceKeys}); the token is returned once, in the answer of the issue, and can never be read again. It is
 * never logged, audited, put in an event, in a sync ledger payload or in an error. Rules:
 * <ul>
 * <li><b>One live credential</b> per student or staff member (a partial unique index backs a pre-check): issuing when one
 * is live is 409; <i>reissue</i> revokes it (with a reason) and issues a new one in the same transaction.</li>
 * <li><b>Who</b>: {@code school.attendance.credentials.manage}; for a student also the LEAD teacher and the managers of a
 * class the student is ACTIVE in (the people who print class sheets). Staff credentials need the permission. Guardians and
 * students never see a credential. A caller without the right is told 403 (never whether the student exists).</li>
 * <li><b>Revocation</b> is final and logged; a withdrawn student's or deactivated staff member's credentials are revoked
 * with LEFT_SCHOOL by {@link CredentialLifecycle}.</li>
 * <li>Every issue and revoke is one audit row (ids, kind, reason, expiry; never the token).</li>
 * </ul>
 */
@Service
@Transactional
public class CredentialService {

	static final int DEFAULT_TTL_DAYS = 365;

	static final int MAX_TTL_DAYS = 1095;

	/** A token: 128 bits as base64url without padding. */
	private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{22}$");

	private static final String SELECT = """
			select c.id, c.student_id, c.staff_id, coalesce(us.display_name, ust.display_name) as display_name, c.issued_at,
			       c.issued_by, c.expires_at, c.revoked_at, c.revoked_reason, c.last_used_at, c.suspected_copy_at
			from attendance_credentials c
			left join users us on us.id = c.student_id
			left join school_staff st on st.id = c.staff_id
			left join users ust on ust.id = st.user_id""";

	private final SecureRandom random = new SecureRandom();

	private final AttendanceAccess access;

	private final AttendanceKeys keys;

	private final SchoolTime time;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final CheckinLedger ledger;

	CredentialService(AttendanceAccess access, AttendanceKeys keys, SchoolTime time, JdbcClient jdbc, AuditLog audit,
			CheckinLedger ledger) {
		this.access = access;
		this.keys = keys;
		this.time = time;
		this.jdbc = jdbc;
		this.audit = audit;
		this.ledger = ledger;
	}

	// --- students ---------------------------------------------------------------------------

	/** A new credential for the student; 409 {@code attendance.credential_exists} while one is live. */
	public IssuedAttendanceCredential issueForStudent(CurrentUser user, UUID schoolId, UUID studentId, Integer ttlDays) {
		requireStudentAccess(user, schoolId, studentId);
		requireActiveStudent(schoolId, studentId);
		this.keys.activeKeyId();
		AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + studentId);
		if (liveStudentCredential(studentId)) {
			throw ApiException.conflict("attendance.credential_exists");
		}
		return insert(user, schoolId, studentId, null, ttl(ttlDays));
	}

	/** Revokes the live credential (any reason but none needed to exist) and issues a new one. */
	public IssuedAttendanceCredential reissueForStudent(CurrentUser user, UUID schoolId, UUID studentId,
			CredentialReason reason, Integer ttlDays) {
		requireStudentAccess(user, schoolId, studentId);
		requireActiveStudent(schoolId, studentId);
		CredentialReason why = reissueReason(reason);
		this.keys.activeKeyId();
		AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + studentId);
		revokeLive(schoolId, "student_id", studentId, why, user.userId());
		return insert(user, schoolId, studentId, null, ttl(ttlDays));
	}

	/** Revokes the student's live credential; 404 {@code attendance.credential_not_found} when there is none. */
	public AttendanceCredentialView revokeForStudent(CurrentUser user, UUID schoolId, UUID studentId, CredentialReason reason) {
		requireStudentAccess(user, schoolId, studentId);
		AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + studentId);
		UUID id = revokeLive(schoolId, "student_id", studentId, reason, user.userId()).stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("attendance.credential_not_found"));
		return view(id);
	}

	/** The student's credentials, newest first (never the token). */
	@Transactional(readOnly = true)
	public List<AttendanceCredentialView> ofStudent(CurrentUser user, UUID schoolId, UUID studentId) {
		requireStudentAccess(user, schoolId, studentId);
		return list(" where c.school_id = :school and c.student_id = :person order by c.issued_at desc, c.id desc limit 20", schoolId,
				studentId);
	}

	// --- staff ------------------------------------------------------------------------------

	public IssuedAttendanceCredential issueForStaff(CurrentUser user, UUID schoolId, UUID staffId, Integer ttlDays) {
		requireStaffAccess(user, schoolId, staffId);
		requireActiveStaff(schoolId, staffId);
		this.keys.activeKeyId();
		AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + staffId);
		if (this.jdbc.sql("select exists (select 1 from attendance_credentials where staff_id = :s and revoked_at is null)")
			.param("s", staffId)
			.query(Boolean.class)
			.single()) {
			throw ApiException.conflict("attendance.credential_exists");
		}
		return insert(user, schoolId, null, staffId, ttl(ttlDays));
	}

	public IssuedAttendanceCredential reissueForStaff(CurrentUser user, UUID schoolId, UUID staffId, CredentialReason reason,
			Integer ttlDays) {
		requireStaffAccess(user, schoolId, staffId);
		requireActiveStaff(schoolId, staffId);
		CredentialReason why = reissueReason(reason);
		this.keys.activeKeyId();
		AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + staffId);
		revokeLive(schoolId, "staff_id", staffId, why, user.userId());
		return insert(user, schoolId, null, staffId, ttl(ttlDays));
	}

	public AttendanceCredentialView revokeForStaff(CurrentUser user, UUID schoolId, UUID staffId, CredentialReason reason) {
		requireStaffAccess(user, schoolId, staffId);
		AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + staffId);
		UUID id = revokeLive(schoolId, "staff_id", staffId, reason, user.userId()).stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("attendance.credential_not_found"));
		return view(id);
	}

	@Transactional(readOnly = true)
	public List<AttendanceCredentialView> ofStaff(CurrentUser user, UUID schoolId, UUID staffId) {
		requireStaffAccess(user, schoolId, staffId);
		return list(" where c.school_id = :school and c.staff_id = :person order by c.issued_at desc, c.id desc limit 20", schoolId,
				staffId);
	}

	// --- the school's credentials -----------------------------------------------------------

	/**
	 * The school's credentials, newest first, keyset paged: filter by kind (STUDENT, STAFF), state (LIVE, EXPIRED, REVOKED)
	 * and {@code suspectedCopy} (live credentials that two scanners read within a minute: the SUSPECTED_COPY alert).
	 * {@code school.attendance.credentials.manage} only.
	 */
	@Transactional(readOnly = true)
	public CursorPage<AttendanceCredentialView> list(CurrentUser user, UUID schoolId, String kind, String state,
			Boolean suspectedCopy, String cursor, Integer limit) {
		this.access.gate().requireVisible(user, schoolId);
		if (!this.access.gate().can(user, schoolId, Permissions.SCHOOL_ATTENDANCE_CREDENTIALS_MANAGE)) {
			throw ApiException.forbidden("attendance.access_denied");
		}
		this.access.requireFeature(schoolId);
		StringBuilder where = new StringBuilder(" where c.school_id = :school");
		if (kind != null) {
			switch (kind) {
				case "STUDENT" -> where.append(" and c.student_id is not null");
				case "STAFF" -> where.append(" and c.staff_id is not null");
				default -> throw ApiException.badRequest("validation.invalid");
			}
		}
		if (state != null) {
			switch (state) {
				case "LIVE" -> where.append(" and c.revoked_at is null and c.expires_at > :now");
				case "EXPIRED" -> where.append(" and c.revoked_at is null and c.expires_at <= :now");
				case "REVOKED" -> where.append(" and c.revoked_at is not null");
				default -> throw ApiException.badRequest("validation.invalid");
			}
		}
		if (Boolean.TRUE.equals(suspectedCopy)) {
			where.append(" and c.suspected_copy_at is not null and c.revoked_at is null");
		}
		int pageSize = Cursors.clampLimit(limit);
		Instant afterAt = null;
		UUID afterId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				afterAt = Instant.parse(parts.get(0));
				afterId = UUID.fromString(parts.get(1));
			}
			catch (RuntimeException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<AttendanceCredentialView> rows = this.jdbc.sql(SELECT + where + """

				  and (cast(:at as timestamptz) is null or (c.issued_at, c.id) < (cast(:at as timestamptz), cast(:id as uuid)))
				order by c.issued_at desc, c.id desc limit :n""")
			.param("school", schoolId)
			.param("now", Timestamp.from(this.time.now()))
			.param("at", (afterAt != null) ? Timestamp.from(afterAt) : null)
			.param("id", afterId)
			.param("n", pageSize + 1)
			.query((rs, i) -> view(rs, this.time.now()))
			.list();
		return CursorPage.fromOverfetch(rows, pageSize, (v) -> Cursors.encode(v.issuedAt().toString(), v.id().toString()));
	}

	// --- the class sheet --------------------------------------------------------------------

	/**
	 * Credentials for the cards of a class (printing is the web's job): every ACTIVE student of the class (or of
	 * {@code studentIds}) without a live credential gets one; with {@code replaceExisting} the others get a new one and the
	 * old is revoked as REPLACED. Answers every token once. The class's LEAD teacher, class managers and
	 * {@code school.attendance.credentials.manage}.
	 */
	public CredentialSheet sheet(CurrentUser user, UUID schoolId, UUID classId, CredentialSheetRequest cmd) {
		ClassRef ref = this.access.viewableClass(user, schoolId, classId);
		this.access.requireFeature(schoolId);
		if (!(this.access.gate().can(user, schoolId, Permissions.SCHOOL_ATTENDANCE_CREDENTIALS_MANAGE)
				|| this.access.classes().canLead(user, classId))) {
			throw ApiException.forbidden("attendance.access_denied");
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		this.keys.activeKeyId();
		Set<UUID> classStudents = this.access.memberships().activeStudentIds(classId);
		List<UUID> students = (cmd.studentIds() == null || cmd.studentIds().isEmpty()) ? new ArrayList<>(classStudents)
				: new ArrayList<>(cmd.studentIds().stream().distinct().toList());
		if (!classStudents.containsAll(students)) {
			throw ApiException.conflict("attendance.student_not_in_class");
		}
		boolean replace = Boolean.TRUE.equals(cmd.replaceExisting());
		Duration ttl = ttl(cmd.ttlDays());
		Map<UUID, PersonCard> cards = this.access.people().cards(students);
		students.sort(Comparator.comparing((UUID id) -> (cards.get(id) != null) ? cards.get(id).displayName().toLowerCase() : "")
			.thenComparing(Comparator.naturalOrder()));
		List<CredentialSheetItem> items = new ArrayList<>();
		List<CredentialSheetSkip> skipped = new ArrayList<>();
		for (UUID studentId : students) {
			AdvisoryLocks.acquire(this.jdbc, "attendance-credential:" + studentId);
			if (liveStudentCredential(studentId)) {
				if (!replace) {
					skipped.add(new CredentialSheetSkip(studentId, "ALREADY_ISSUED"));
					continue;
				}
				revokeLive(schoolId, "student_id", studentId, CredentialReason.REPLACED, user.userId());
			}
			IssuedAttendanceCredential issued = insert(user, schoolId, studentId, null, ttl);
			PersonCard card = cards.get(studentId);
			items.add(new CredentialSheetItem(studentId, (card != null) ? card.displayName() : "", issued.credential().id(),
					issued.qrPayload(), issued.credential().issuedAt(), issued.credential().expiresAt()));
		}
		return new CredentialSheet(items, skipped);
	}

	// --- scanning (used by CheckinService) --------------------------------------------------

	/** A credential found by its token, as the scanner's flow needs it. */
	record ScanCredential(UUID id, UUID schoolId, UUID studentId, UUID staffId, String keyId, Instant expiresAt, Instant revokedAt,
			Instant suspectedCopyAt) {

		boolean revoked() {
			return this.revokedAt != null;
		}

		boolean expired(Instant now) {
			return !this.expiresAt.isAfter(now);
		}

	}

	/**
	 * The credential of a token, if the token is well formed and its hash (under any configured pepper key) is stored. The
	 * token is only hashed here: it is never logged and never leaves this method.
	 */
	Optional<ScanCredential> find(String token) {
		if (token == null || !TOKEN.matcher(token).matches()) {
			return Optional.empty();
		}
		Map<String, String> hashes = this.keys.candidateHashes(token);
		if (hashes.isEmpty()) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				select id, school_id, student_id, staff_id, key_id, expires_at, revoked_at, suspected_copy_at
				from attendance_credentials where token_hash in (:hashes)""")
			.param("hashes", hashes.values())
			.query((rs, i) -> new ScanCredential(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "student_id"),
					Rows.uuid(rs, "staff_id"), rs.getString("key_id"), Rows.instant(rs, "expires_at"), Rows.instant(rs, "revoked_at"),
					Rows.instant(rs, "suspected_copy_at")))
			.optional();
	}

	/**
	 * Notes a successful read of the credential: its last use and, when it was hashed under an older pepper key, the hash
	 * under the active key (rotation: old credentials move to the new key by being used).
	 */
	void used(ScanCredential credential, String token, Instant now) {
		if (credential.keyId().equals(this.keys.activeKeyId())) {
			this.jdbc.sql("update attendance_credentials set last_used_at = :now where id = :id")
				.param("now", Timestamp.from(now))
				.param("id", credential.id())
				.update();
		}
		else {
			this.jdbc.sql("""
					update attendance_credentials set last_used_at = :now, token_hash = :hash, key_id = :key where id = :id""")
				.param("now", Timestamp.from(now))
				.param("hash", this.keys.hash(token))
				.param("key", this.keys.activeKeyId())
				.param("id", credential.id())
				.update();
		}
	}

	/**
	 * Two different scanners reading one credential within a minute (SUSPECTED_COPY): flags the credential and writes one
	 * audit row (ids only). The scan itself is not refused: the scanner sees the first name and avatar, the school decides.
	 */
	void noteRead(ScanCredential credential, UUID scannerUserId, Instant now) {
		if (!this.ledger.readByAnotherScannerSince(credential.id(), scannerUserId, now.minus(Duration.ofMinutes(1)))) {
			return;
		}
		boolean fresh = credential.suspectedCopyAt() == null || credential.suspectedCopyAt().isBefore(now.minus(Duration.ofMinutes(1)));
		this.jdbc.sql("update attendance_credentials set suspected_copy_at = :now where id = :id")
			.param("now", Timestamp.from(now))
			.param("id", credential.id())
			.update();
		if (fresh) {
			this.audit.record(AuditEntry.of("attendance.credential.suspected_copy", AuditOutcome.SUCCESS)
				.target("attendance_credential", credential.id())
				.school(credential.schoolId())
				.metadata("studentId", (credential.studentId() != null) ? credential.studentId().toString() : null)
				.metadata("staffId", (credential.staffId() != null) ? credential.staffId().toString() : null));
		}
	}

	/** Revokes every live credential of a leaver (a withdrawn student, a deactivated staff member); idempotent. */
	int revokeForLeaver(UUID schoolId, boolean student, UUID personId, UUID actorId) {
		return revokeLive(schoolId, student ? "student_id" : "staff_id", personId, CredentialReason.LEFT_SCHOOL, actorId).size();
	}

	// --- internals --------------------------------------------------------------------------

	private IssuedAttendanceCredential insert(CurrentUser user, UUID schoolId, UUID studentId, UUID staffId, Duration ttl) {
		byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		UUID id = Ids.newId();
		Instant now = this.time.now();
		Instant expires = now.plus(ttl);
		this.jdbc.sql("""
				insert into attendance_credentials (id, school_id, student_id, staff_id, token_hash, key_id, issued_by, issued_at,
				                                    expires_at)
				values (:id, :school, :student, :staff, :hash, :key, :by, :now, :exp)""")
			.param("id", id)
			.param("school", schoolId)
			.param("student", studentId)
			.param("staff", staffId)
			.param("hash", this.keys.hash(token))
			.param("key", this.keys.activeKeyId())
			.param("by", user.userId())
			.param("now", Timestamp.from(now))
			.param("exp", Timestamp.from(expires))
			.update();
		this.audit.record(AuditEntry.of("attendance.credential.issue", AuditOutcome.SUCCESS)
			.target("attendance_credential", id)
			.school(schoolId)
			.metadata("subjectKind", (studentId != null) ? "STUDENT" : "STAFF")
			.metadata("studentId", (studentId != null) ? studentId.toString() : null)
			.metadata("staffId", (staffId != null) ? staffId.toString() : null)
			.metadata("expiresAt", expires.toString()));
		return new IssuedAttendanceCredential(view(id), token);
	}

	/** Revokes the person's live credentials (at most one); returns their ids. {@code column} is a fixed column name. */
	private List<UUID> revokeLive(UUID schoolId, String column, UUID personId, CredentialReason reason, UUID actorId) {
		if (!column.equals("student_id") && !column.equals("staff_id")) {
			throw new IllegalArgumentException(column);
		}
		List<UUID> ids = this.jdbc.sql("update attendance_credentials set revoked_at = :now, revoked_by = coalesce(:by, issued_by), revoked_reason = :reason where "
				+ column + " = :person and school_id = :school and revoked_at is null returning id")
			.param("now", Timestamp.from(this.time.now()))
			.param("by", actorId)
			.param("reason", reason.name())
			.param("person", personId)
			.param("school", schoolId)
			.query(UUID.class)
			.list();
		for (UUID id : ids) {
			this.audit.record(AuditEntry.of("attendance.credential.revoke", AuditOutcome.SUCCESS)
				.actor(actorId)
				.target("attendance_credential", id)
				.school(schoolId)
				.metadata("subjectKind", column.equals("student_id") ? "STUDENT" : "STAFF")
				.metadata("reason", reason.name()));
		}
		return ids;
	}

	private boolean liveStudentCredential(UUID studentId) {
		return this.jdbc.sql("select exists (select 1 from attendance_credentials where student_id = :s and revoked_at is null)")
			.param("s", studentId)
			.query(Boolean.class)
			.single();
	}

	private static Duration ttl(Integer ttlDays) {
		int days = (ttlDays != null) ? ttlDays : DEFAULT_TTL_DAYS;
		if (days < 1 || days > MAX_TTL_DAYS) {
			throw ApiException.badRequest("attendance.credential_invalid_ttl", MAX_TTL_DAYS);
		}
		return Duration.ofDays(days);
	}

	private static CredentialReason reissueReason(CredentialReason reason) {
		CredentialReason why = (reason != null) ? reason : CredentialReason.REPLACED;
		if (why == CredentialReason.LEFT_SCHOOL) {
			throw ApiException.badRequest("attendance.credential_invalid_reason");
		}
		return why;
	}

	private AttendanceCredentialView view(UUID id) {
		return this.jdbc.sql(SELECT + " where c.id = :id")
			.param("id", id)
			.query((rs, i) -> view(rs, this.time.now()))
			.single();
	}

	private List<AttendanceCredentialView> list(String tail, UUID schoolId, UUID personId) {
		Instant now = this.time.now();
		return this.jdbc.sql(SELECT + tail)
			.param("school", schoolId)
			.param("person", personId)
			.query((rs, i) -> view(rs, now))
			.list();
	}

	private static AttendanceCredentialView view(java.sql.ResultSet rs, Instant now) throws java.sql.SQLException {
		Instant expires = Rows.instant(rs, "expires_at");
		Instant revoked = Rows.instant(rs, "revoked_at");
		UUID student = Rows.uuid(rs, "student_id");
		String state = (revoked != null) ? "REVOKED" : (!expires.isAfter(now) ? "EXPIRED" : "LIVE");
		return new AttendanceCredentialView(Rows.uuid(rs, "id"), (student != null) ? "STUDENT" : "STAFF", student,
				Rows.uuid(rs, "staff_id"), rs.getString("display_name"), Rows.instant(rs, "issued_at"), Rows.uuid(rs, "issued_by"),
				expires, revoked, rs.getString("revoked_reason"), Rows.instant(rs, "last_used_at"),
				Rows.instant(rs, "suspected_copy_at"), state);
	}

	// --- access -----------------------------------------------------------------------------

	/**
	 * Visible school (404), the permission or the LEAD of a class the student is in (403 for everybody else, before anything
	 * is said about the student), then the student must exist (404 {@code student.not_found}).
	 */
	private void requireStudentAccess(CurrentUser user, UUID schoolId, UUID studentId) {
		this.access.gate().requireVisible(user, schoolId);
		this.access.requireFeature(schoolId);
		if (!this.access.gate().can(user, schoolId, Permissions.SCHOOL_ATTENDANCE_CREDENTIALS_MANAGE) && !leadsStudent(user, studentId)) {
			throw ApiException.forbidden("attendance.access_denied");
		}
		if (!this.access.knownStudent(schoolId, studentId)) {
			throw ApiException.notFound("student.not_found");
		}
	}

	private boolean leadsStudent(CurrentUser user, UUID studentId) {
		List<StudentClass> classes = this.access.memberships().activeClassesOf(List.of(studentId)).getOrDefault(studentId, List.of());
		return classes.stream().anyMatch((c) -> this.access.classes().canLead(user, c.classId()));
	}

	private void requireActiveStudent(UUID schoolId, UUID studentId) {
		if (!this.access.activeStudent(schoolId, studentId)) {
			throw ApiException.conflict("attendance.credential_person_not_active");
		}
	}

	private void requireActiveStaff(UUID schoolId, UUID staffId) {
		boolean active = this.jdbc.sql("select exists (select 1 from school_staff where id = :id and school_id = :s and status = 'ACTIVE')")
			.param("id", staffId)
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!active) {
			throw ApiException.conflict("staff_attendance.staff_not_active");
		}
	}

	private void requireStaffAccess(CurrentUser user, UUID schoolId, UUID staffId) {
		this.access.gate().requireVisible(user, schoolId);
		this.access.requireFeature(schoolId);
		if (!this.access.gate().can(user, schoolId, Permissions.SCHOOL_ATTENDANCE_CREDENTIALS_MANAGE)) {
			throw ApiException.forbidden("attendance.access_denied");
		}
		boolean known = this.jdbc.sql("select exists (select 1 from school_staff where id = :id and school_id = :s)")
			.param("id", staffId)
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!known) {
			throw ApiException.notFound("staff_attendance.staff_not_found");
		}
	}

}
