package com.nemaz.school.people.application;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.identity.CodeHasher;
import com.nemaz.school.identity.LoginCodeRedeemer;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserProfileView;
import com.nemaz.school.people.LoginCodeIssued;
import com.nemaz.school.people.LoginCodeRevoked;
import com.nemaz.school.people.application.LoginCodeFormat.Kind;
import com.nemaz.school.people.application.LoginCodeFormat.Parsed;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student login codes (decision D6, R-31): issue per class sheet, revoke, and redeem for sign-in.
 * <ul>
 * <li><b>Issue:</b> class teachers or {@code class.manage}. Every issue revokes the student's previous live codes
 * of the school (reason REPLACED) and returns the new plaintext codes exactly once; only
 * {@code HMAC-SHA256(pepper, code)} is stored ({@link CodeHasher}). Codes live {@code ttlDays} (default
 * setting {@code students.login_code.ttl_days}, at most 730).</li>
 * <li><b>Redeem:</b> unknown, revoked, expired codes, students without an ACTIVE enrollment in the code's school
 * and inactive schools all look the same: empty.</li>
 * <li><b>Audit:</b> one row per code issued and revoked; codes never reach audit, events or logs.</li>
 * </ul>
 */
@Service
@Transactional
public class LoginCodeService implements LoginCodeRedeemer {

	static final String PURPOSE = "login-code";

	static final int MAX_TTL_DAYS = 730;

	private final SecureRandom random = new SecureRandom();

	private final JdbcClient jdbc;

	private final ClassAccess access;

	private final ClassMemberships memberships;

	private final CodeHasher hasher;

	private final UserDirectory users;

	private final SettingsService settings;

	private final TenantDirectory directory;

	private final SchoolGate gate;

	private final StudentAccess studentAccess;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	LoginCodeService(JdbcClient jdbc, ClassAccess access, ClassMemberships memberships, CodeHasher hasher,
			UserDirectory users, SettingsService settings, TenantDirectory directory, SchoolGate gate, StudentAccess studentAccess, AuditLog audit,
			ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.memberships = memberships;
		this.hasher = hasher;
		this.users = users;
		this.settings = settings;
		this.directory = directory;
		this.gate = gate;
		this.studentAccess = studentAccess;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public List<IssuedCode> issue(CurrentUser user, UUID schoolId, UUID classId, IssueRequest r) {
		ClassRef ref = this.access.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.access.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		if (!this.access.canTeach(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		Set<UUID> classStudents = this.memberships.activeStudentIds(classId);
		Set<UUID> students = (r.studentIds() == null || r.studentIds().isEmpty()) ? classStudents
				: new LinkedHashSet<>(r.studentIds());
		if (!classStudents.containsAll(students)) {
			throw ApiException.conflict("login_code.student_not_in_class");
		}
		if (students.isEmpty()) {
			return List.of();
		}
		int ttlDays = (r.ttlDays() != null) ? r.ttlDays()
				: this.settings.getInt("students.login_code.ttl_days", SettingScope.school(schoolId)).orElse(365);
		if (ttlDays < 1 || ttlDays > MAX_TTL_DAYS) {
			throw ApiException.badRequest("login_code.invalid_ttl", MAX_TTL_DAYS);
		}
		Instant now = this.clock.instant();
		Instant expiresAt = now.plus(Duration.ofDays(ttlDays));
		CodeType type = (r.type() != null) ? r.type() : CodeType.TEXT;
		for (UUID studentId : students) {
			revokeLive(schoolId, studentId, "REPLACED", user.userId(), now);
		}
		Map<UUID, UserProfileView> profiles = this.users.profiles(students);
		List<IssuedCode> issued = new ArrayList<>();
		for (UUID studentId : students) {
			String qr = (type == CodeType.QR || type == CodeType.BOTH) ? store(schoolId, classId, studentId, Kind.QR,
					user.userId(), expiresAt, now) : null;
			String text = (type == CodeType.TEXT || type == CodeType.BOTH) ? store(schoolId, classId, studentId,
					Kind.TEXT, user.userId(), expiresAt, now) : null;
			UserProfileView profile = profiles.get(studentId);
			issued.add(new IssuedCode(studentId, (profile != null) ? profile.displayName() : "", qr,
					(text != null) ? LoginCodeFormat.display(text) : null, expiresAt));
		}
		return issued;
	}

	/** Revokes one code of the class's sheet. */
	public void revoke(CurrentUser user, UUID schoolId, UUID classId, UUID codeId) {
		this.access.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElseThrow(() -> ApiException.notFound("class.not_found"));
		if (!this.access.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		if (!this.access.canTeach(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		UUID studentId = this.jdbc.sql("""
				select student_id from student_login_codes where id = :id and class_id = :c and school_id = :s""")
			.param("id", codeId)
			.param("c", classId)
			.param("s", schoolId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("login_code.not_found"));
		int revoked = this.jdbc.sql("update student_login_codes set revoked_at = :now where id = :id and revoked_at is null")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", codeId)
			.update();
		if (revoked > 0) {
			revoked(codeId, schoolId, studentId, "REVOKED", user.userId());
		}
	}

	/** Revokes every live code of a student (manager of students, or a teacher of the student). */
	public int revokeStudentCodes(CurrentUser user, UUID schoolId, UUID studentId) {
		requireStudentAccess(user, schoolId, studentId);
		return revokeLive(schoolId, studentId, "REVOKED", user.userId(), this.clock.instant());
	}

	/** Metadata of the student's codes that are not revoked, newest first (never the codes). */
	@Transactional(readOnly = true)
	public List<LoginCodeInfo> studentCodes(CurrentUser user, UUID schoolId, UUID studentId) {
		requireStudentAccess(user, schoolId, studentId);
		Instant now = this.clock.instant();
		return this.jdbc.sql("""
				select id, code_type, class_id, expires_at, last_used_at, created_at from student_login_codes
				where school_id = :s and student_id = :st and revoked_at is null order by created_at desc, id desc""")
			.param("s", schoolId)
			.param("st", studentId)
			.query((rs, i) -> new LoginCodeInfo(Rows.uuid(rs, "id"), CodeType.valueOf(rs.getString("code_type")),
					Rows.uuid(rs, "class_id"), Rows.instant(rs, "expires_at"), Rows.instant(rs, "last_used_at"),
					Rows.instant(rs, "created_at"),
					Rows.instant(rs, "expires_at") != null && !Rows.instant(rs, "expires_at").isAfter(now)))
			.list();
	}

	/** Revokes the student's live codes in the school (used by withdrawal); returns how many. */
	int revokeAllForWithdrawal(UUID schoolId, UUID studentId, UUID actorId) {
		return revokeLive(schoolId, studentId, "WITHDRAWN", actorId, this.clock.instant());
	}

	// --- redeem (called by identity) --------------------------------------------------------

	@Override
	public Optional<LoginCodeGrant> redeem(String submitted, Instant now) {
		Parsed parsed = LoginCodeFormat.parse(submitted).orElse(null);
		if (parsed == null) {
			return Optional.empty();
		}
		String hash = this.hasher.hmac(PURPOSE, parsed.canonical());
		return this.jdbc.sql("""
				select c.id, c.student_id, c.school_id from student_login_codes c
				where c.code_hash = :h and c.revoked_at is null and (c.expires_at is null or c.expires_at > :now)
				  and exists (select 1 from school_students ss where ss.school_id = c.school_id
				              and ss.student_id = c.student_id and ss.status = 'ACTIVE')
				for update of c""")
			.param("h", hash)
			.param("now", Timestamp.from(now))
			.query((rs, i) -> new LoginCodeGrant(Rows.uuid(rs, "student_id"), Rows.uuid(rs, "school_id"),
					Rows.uuid(rs, "id")))
			.optional()
			.filter((grant) -> this.directory.school(grant.schoolId()).filter(TenantDirectory.SchoolRef::isActive).isPresent())
			.map((grant) -> {
				this.jdbc.sql("update student_login_codes set last_used_at = :now where id = :id")
					.param("now", Timestamp.from(now))
					.param("id", grant.codeId())
					.update();
				return grant;
			});
	}

	// --- helpers ----------------------------------------------------------------------------

	private String store(UUID schoolId, UUID classId, UUID studentId, Kind kind, UUID issuedBy, Instant expiresAt,
			Instant now) {
		String code = (kind == Kind.QR) ? LoginCodeFormat.newQrToken(this.random) : LoginCodeFormat.newText(this.random);
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into student_login_codes (id, student_id, school_id, class_id, code_type, code_hash, issued_by,
				                                 expires_at, created_at)
				values (:id, :st, :s, :c, :type, :hash, :by, :exp, :now)""")
			.param("id", id)
			.param("st", studentId)
			.param("s", schoolId)
			.param("c", classId)
			.param("type", kind.name())
			.param("hash", this.hasher.hmac(PURPOSE, code))
			.param("by", issuedBy)
			.param("exp", Timestamp.from(expiresAt))
			.param("now", Timestamp.from(now))
			.update();
		this.events.publishEvent(new LoginCodeIssued(id, schoolId, classId, studentId, kind.name(), expiresAt, issuedBy));
		this.audit.record(AuditEntry.of("login_code.issue", AuditOutcome.SUCCESS)
			.target("login_code", id)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("classId", classId.toString())
			.metadata("type", kind.name())
			.metadata("expiresAt", expiresAt.toString()));
		return code;
	}

	private int revokeLive(UUID schoolId, UUID studentId, String reason, UUID actorId, Instant now) {
		List<UUID> ids = this.jdbc.sql("""
				update student_login_codes set revoked_at = :now where school_id = :s and student_id = :st and revoked_at is null
				returning id""")
			.param("now", Timestamp.from(now))
			.param("s", schoolId)
			.param("st", studentId)
			.query(UUID.class)
			.list();
		for (UUID id : ids) {
			revoked(id, schoolId, studentId, reason, actorId);
		}
		return ids.size();
	}

	private void revoked(UUID codeId, UUID schoolId, UUID studentId, String reason, UUID actorId) {
		this.events.publishEvent(new LoginCodeRevoked(codeId, schoolId, studentId, reason, actorId));
		this.audit.record(AuditEntry.of("login_code.revoke", AuditOutcome.SUCCESS)
			.target("login_code", codeId)
			.school(schoolId)
			.metadata("studentId", studentId.toString())
			.metadata("reason", reason));
	}

	/** Managers of students, or a teacher of one of the student's classes; 404 for strangers, 403 for other viewers. */
	private void requireStudentAccess(CurrentUser user, UUID schoolId, UUID studentId) {
		this.gate.requireVisible(user, schoolId);
		if (this.studentAccess.level(user, schoolId, studentId) == null) {
			throw ApiException.notFound("student.not_found");
		}
		if (!this.studentAccess.mayAdminister(user, schoolId, studentId)) {
			throw ApiException.forbidden("student.access_denied");
		}
	}

	public enum CodeType {

		QR, TEXT, BOTH

	}

	public record IssueRequest(List<UUID> studentIds, CodeType type, Integer ttlDays) {
	}

	/** The only time the codes are visible. {@code qrToken} goes inside a QR code; {@code textCode} is {@code XXXXX-XXXXX}. */
	public record IssuedCode(@NotNull UUID studentId, @NotNull String displayName, String qrToken, String textCode,
			@NotNull Instant expiresAt) {

		@Override
		public String toString() {
			return "IssuedCode[redacted]";
		}

	}

	public record LoginCodeInfo(@NotNull UUID id, @NotNull @Schema(description = "QR or TEXT") CodeType type, UUID classId,
			Instant expiresAt, Instant lastUsedAt, @NotNull Instant createdAt, @NotNull boolean expired) {
	}

}
