package com.nemaz.school.people.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.authorization.RoleAssignments;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.i18n.SupportedLocales;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Constraints;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Masking;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.identity.UserAccounts.ProvisionRequest;
import com.nemaz.school.identity.UserAccounts.ProvisionedUser;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserProfileView;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.notifications.LocalizedText;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.people.StaffAdded;
import com.nemaz.school.people.StaffDeactivated;
import com.nemaz.school.system.EmailSender;
import com.nemaz.school.system.SmsSender;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.CampusRef;
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
 * A school's staff (R-02): teachers, administrators and support staff.
 * <ul>
 * <li><b>Adding:</b> {@code school.staff.manage}. The administrator vouches for the destination exactly as for
 * {@code POST /platform/users}: the person is found by their normalized phone or email, or an ACTIVE account
 * with that verified destination is created. Never a duplicate user; adding someone who is live staff already is
 * 409 {@code staff.already_member}. A TEACHER gets a {@code teacher_profiles} row. An ADMINISTRATOR may also be given
 * SCHOOL_ADMIN, CAMPUS_ADMIN or SCHOOL_STAFF through the role-assignment service (the actor must hold every
 * permission of the role at that scope: no escalation).</li>
 * <li><b>Welcome:</b> an in-app notification {@code STAFF_ADDED}; a newly created account also gets an SMS or
 * email telling them to sign in with that phone or email (sent after commit).</li>
 * <li><b>Deactivating:</b> status FORMER with {@code ended_on}; their class teaching ends (a class may be left
 * without a lead: administrators reassign). Administrative roles are not touched.</li>
 * <li><b>Reading:</b> managers see every status with masked contact details; other ACTIVE staff see the ACTIVE
 * colleagues without contact details (teachers pick co-teachers from it).</li>
 * </ul>
 */
@Service
@Transactional
public class StaffService {

	private static final Logger log = LoggerFactory.getLogger(StaffService.class);

	private final JdbcClient jdbc;

	private final UserAccounts accounts;

	private final UserDirectory users;

	private final RoleAssignments roles;

	private final ClassMemberships classes;

	private final TenantDirectory directory;

	private final SchoolGate gate;

	private final NotificationService notifications;

	private final MediaService media;

	private final MessageSource messages;

	private final SmsSender sms;

	private final EmailSender email;

	private final TaskExecutor executor;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final SupportedLocales languages;

	StaffService(JdbcClient jdbc, UserAccounts accounts, UserDirectory users, RoleAssignments roles,
			ClassMemberships classes, TenantDirectory directory, SchoolGate gate, NotificationService notifications,
			MediaService media, MessageSource messages, SmsSender sms, EmailSender email,
			@Qualifier("applicationTaskExecutor") TaskExecutor executor, AuditLog audit,
			ApplicationEventPublisher events, Clock clock, SupportedLocales languages) {
		this.languages = languages;
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.users = users;
		this.roles = roles;
		this.classes = classes;
		this.directory = directory;
		this.gate = gate;
		this.notifications = notifications;
		this.media = media;
		this.messages = messages;
		this.sms = sms;
		this.email = email;
		this.executor = executor;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public StaffView add(CurrentUser actor, UUID schoolId, AddStaff r) {
		this.gate.require(actor, schoolId, Permissions.SCHOOL_STAFF_MANAGE);
		Provisioned provisioned = provision(actor, actor.userId(), schoolId, r, null);
		return get(actor, schoolId, provisioned.staffId());
	}

	/**
	 * Adds a person to the staff for the roster import, which checked {@code school.import.manage}: the same
	 * provisioning as {@link #add} (account found or created by phone or email, profile, notification, welcome
	 * message) but never an administrative role.
	 */
	public Provisioned addForImport(UUID actorId, UUID schoolId, AddStaff r, String externalRef) {
		if (r.adminRole() != null) {
			throw ApiException.badRequest("staff.admin_role_not_allowed");
		}
		return provision(null, actorId, schoolId, r, externalRef);
	}

	/** Changes details of a staff member for the roster import (absent fields stay). */
	public void updateForImport(UUID actorId, UUID schoolId, UUID staffId, String jobTitle, UUID campusId,
			String externalRef) {
		StaffRow before = find(schoolId, staffId);
		UUID campus = (campusId != null) ? validCampus(schoolId, campusId) : before.campusId();
		this.jdbc.sql("""
				update school_staff set job_title = :title, campus_id = :campus, external_ref = coalesce(:ext, external_ref),
				       updated_at = now(), version = version + 1 where id = :id""")
			.param("title", (jobTitle != null) ? blankToNull(jobTitle) : before.jobTitle())
			.param("campus", campus)
			.param("ext", blankToNull(externalRef))
			.param("id", staffId)
			.update();
		this.audit.record(AuditEntry.of("staff.update", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("school_staff", staffId)
			.school(schoolId)
			.metadata("by", "import"));
	}

	/** {@code roleGrantor} is the acting user for an administrative role, null when no role may be granted. */
	private Provisioned provision(CurrentUser roleGrantor, UUID actorId, UUID schoolId, AddStaff r, String externalRef) {
		SchoolRef school = this.directory.school(schoolId).orElseThrow(() -> ApiException.notFound("school.not_found"));
		UUID campusId = validCampus(schoolId, r.campusId());
		if (r.adminRole() != null && r.staffType() != StaffType.ADMINISTRATOR) {
			throw ApiException.badRequest("staff.admin_role_not_allowed");
		}
		if (r.adminRole() == AdminRole.CAMPUS_ADMIN && campusId == null) {
			throw ApiException.badRequest("staff.campus_required");
		}
		String language = (r.preferredLanguage() != null && !r.preferredLanguage().isBlank()) ? r.preferredLanguage()
				: school.defaultLanguageCode();
		ProvisionedUser person = this.accounts.findOrCreateVerified(new ProvisionRequest(r.phone(), r.email(),
				(r.country() != null) ? r.country() : school.countryCode(), r.givenName(), r.familyName(), r.displayName(),
				language));
		boolean already = this.jdbc.sql("""
				select exists (select 1 from school_staff where school_id = :s and user_id = :u and status in ('INVITED', 'ACTIVE'))""")
			.param("s", schoolId)
			.param("u", person.id())
			.query(Boolean.class)
			.single();
		if (already) {
			throw ApiException.conflict("staff.already_member");
		}
		if (r.staffType() == StaffType.TEACHER) {
			this.jdbc.sql("insert into teacher_profiles (user_id) values (:u) on conflict do nothing").param("u", person.id()).update();
		}
		UUID staffId = Ids.newId();
		try {
			this.jdbc.sql("""
					insert into school_staff (id, school_id, user_id, campus_id, staff_type, job_title, employee_number, external_ref,
					                          status, started_on)
					values (:id, :s, :u, :campus, :type, :title, :number, :ext, 'ACTIVE', :today)""")
				.param("id", staffId)
				.param("s", schoolId)
				.param("u", person.id())
				.param("campus", campusId)
				.param("type", r.staffType().name())
				.param("title", blankToNull(r.jobTitle()))
				.param("number", blankToNull(r.employeeNumber()))
				.param("ext", blankToNull(externalRef))
				.param("today", LocalDate.now(this.clock))
				.update();
		}
		catch (DataIntegrityViolationException ex) {
			if (Constraints.violated(ex, "ux_school_staff_active")) {
				throw ApiException.conflict("staff.already_member");
			}
			throw ex;
		}
		if (r.adminRole() != null) {
			AccessScope scope = (r.adminRole() == AdminRole.CAMPUS_ADMIN) ? AccessScope.campus(schoolId, campusId)
					: AccessScope.school(schoolId);
			this.roles.grantWithinScope(roleGrantor, person.id(), r.adminRole().name(), scope);
		}
		notifyAdded(school, staffId, person, r.staffType(), language);
		this.events.publishEvent(new StaffAdded(schoolId, staffId, person.id(), r.staffType().name(), person.created(),
				actorId));
		this.audit.record(AuditEntry.of("staff.add", AuditOutcome.SUCCESS)
			.actor(actorId)
			.target("school_staff", staffId)
			.school(schoolId)
			.metadata("userId", person.id().toString())
			.metadata("staffType", r.staffType().name())
			.metadata("adminRole", (r.adminRole() != null) ? r.adminRole().name() : null)
			.metadata("accountCreated", person.created())
			.metadata("phone", Masking.phone(person.phoneE164()))
			.metadata("email", Masking.email(person.email())));
		return new Provisioned(staffId, person.id(), person.created());
	}

	public record Provisioned(UUID staffId, UUID userId, boolean accountCreated) {
	}

	@Transactional(readOnly = true)
	public CursorPage<StaffView> list(CurrentUser user, UUID schoolId, StaffType type, StaffStatus status, UUID campusId,
			String q, String cursor, Integer limit) {
		boolean manager = requireColleague(user, schoolId);
		int size = Cursors.clampLimit(limit);
		StringBuilder where = new StringBuilder("ss.school_id = :s");
		Map<String, Object> params = new HashMap<>();
		params.put("s", schoolId);
		StaffStatus effective = manager ? status : StaffStatus.ACTIVE;
		if (effective != null) {
			where.append(" and ss.status = :status");
			params.put("status", effective.name());
		}
		if (type != null) {
			where.append(" and ss.staff_type = :type");
			params.put("type", type.name());
		}
		if (campusId != null) {
			where.append(" and ss.campus_id = :campus");
			params.put("campus", campusId);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and u.display_name ilike :q escape '\\'");
			params.put("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
		}
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			where.append(" and (u.display_name, ss.id) > (:cname, :cid)");
			params.put("cname", parts.get(0));
			try {
				params.put("cid", UUID.fromString(parts.get(1)));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		// Reads users for sorting and name search: the one place the people module joins identity's table (read only).
		List<StaffRow> rows = this.jdbc.sql(SELECT + " where " + where + " order by u.display_name, ss.id limit " + (size + 1))
			.params(params)
			.query(StaffService::row)
			.list();
		CursorPage<StaffRow> page = CursorPage.fromOverfetch(rows, size,
				(row) -> Cursors.encode(row.displayName(), row.id().toString()));
		return new CursorPage<>(views(page.items(), manager), page.nextCursor());
	}

	@Transactional(readOnly = true)
	public StaffView get(CurrentUser user, UUID schoolId, UUID staffId) {
		boolean manager = requireColleague(user, schoolId);
		StaffRow row = find(schoolId, staffId);
		if (!manager && row.status() != StaffStatus.ACTIVE) {
			throw ApiException.notFound("staff.not_found");
		}
		return views(List.of(row), manager).getFirst();
	}

	public StaffView update(CurrentUser actor, UUID schoolId, UUID staffId, String jobTitle, UUID campusId,
			String employeeNumber) {
		this.gate.require(actor, schoolId, Permissions.SCHOOL_STAFF_MANAGE);
		StaffRow before = find(schoolId, staffId);
		UUID campus = (campusId != null) ? validCampus(schoolId, campusId) : before.campusId();
		this.jdbc.sql("""
				update school_staff set job_title = :title, campus_id = :campus, employee_number = :number, updated_at = now(),
				       version = version + 1 where id = :id""")
			.param("title", (jobTitle != null) ? blankToNull(jobTitle) : before.jobTitle())
			.param("campus", campus)
			.param("number", (employeeNumber != null) ? blankToNull(employeeNumber) : before.employeeNumber())
			.param("id", staffId)
			.update();
		StaffRow after = find(schoolId, staffId);
		this.audit.record(AuditEntry.of("staff.update", AuditOutcome.SUCCESS)
			.target("school_staff", staffId)
			.school(schoolId)
			.changes(Map.of("jobTitle", String.valueOf(before.jobTitle()), "campusId", String.valueOf(before.campusId())),
					Map.of("jobTitle", String.valueOf(after.jobTitle()), "campusId", String.valueOf(after.campusId()))));
		return views(List.of(after), true).getFirst();
	}

	public StaffView deactivate(CurrentUser actor, UUID schoolId, UUID staffId) {
		this.gate.require(actor, schoolId, Permissions.SCHOOL_STAFF_MANAGE);
		StaffRow row = this.jdbc.sql(SELECT + " where ss.id = :id and ss.school_id = :s for update of ss")
			.param("id", staffId)
			.param("s", schoolId)
			.query(StaffService::row)
			.optional()
			.orElseThrow(() -> ApiException.notFound("staff.not_found"));
		if (row.status() != StaffStatus.ACTIVE && row.status() != StaffStatus.INVITED
				&& row.status() != StaffStatus.SUSPENDED) {
			throw ApiException.conflict("staff.not_active");
		}
		this.jdbc.sql("""
				update school_staff set status = 'FORMER', ended_on = :today, updated_at = now(), version = version + 1
				where id = :id""")
			.param("today", LocalDate.now(this.clock))
			.param("id", staffId)
			.update();
		int ended = (row.staffType() == StaffType.TEACHER)
				? this.classes.endTeacherMemberships(schoolId, row.userId(), actor.userId()) : 0;
		this.events.publishEvent(new StaffDeactivated(schoolId, staffId, row.userId(), row.staffType().name(), actor.userId()));
		this.audit.record(AuditEntry.of("staff.deactivate", AuditOutcome.SUCCESS)
			.target("school_staff", staffId)
			.school(schoolId)
			.metadata("userId", row.userId().toString())
			.metadata("classMembershipsEnded", ended));
		return views(List.of(find(schoolId, staffId)), true).getFirst();
	}

	// --- helpers ------------------------------------------------------------------------

	/** The caller is a manager (true) or an ACTIVE colleague (false); 404 for outsiders, 403 for students. */
	private boolean requireColleague(CurrentUser user, UUID schoolId) {
		if (this.gate.can(user, schoolId, Permissions.SCHOOL_STAFF_MANAGE)) {
			return true;
		}
		this.gate.requireVisible(user, schoolId);
		boolean staff = this.jdbc.sql("select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("u", user.userId())
			.query(Boolean.class)
			.single();
		if (!staff) {
			throw ApiException.forbidden("staff.access_denied");
		}
		return false;
	}

	private List<StaffView> views(List<StaffRow> rows, boolean manager) {
		Map<UUID, UserProfileView> profiles = this.users.profiles(rows.stream().map(StaffRow::userId).toList());
		Map<UUID, MediaSummary> avatars = this.media.summaries(profiles.values()
			.stream()
			.map(UserProfileView::avatarMediaId)
			.filter(java.util.Objects::nonNull)
			.toList());
		List<StaffView> views = new ArrayList<>();
		for (StaffRow row : rows) {
			UserProfileView p = profiles.get(row.userId());
			views.add(new StaffView(row.id(), row.userId(), (p != null) ? p.displayName() : row.displayName(),
					(p != null && p.avatarMediaId() != null) ? avatars.get(p.avatarMediaId()) : null, row.staffType(),
					row.status(), row.jobTitle(), row.campusId(), row.startedOn(), row.endedOn(),
					manager ? row.employeeNumber() : null, (manager && p != null) ? p.maskedPhone() : null,
					(manager && p != null) ? p.maskedEmail() : null));
		}
		return views;
	}

	private StaffRow find(UUID schoolId, UUID staffId) {
		return this.jdbc.sql(SELECT + " where ss.id = :id and ss.school_id = :s")
			.param("id", staffId)
			.param("s", schoolId)
			.query(StaffService::row)
			.optional()
			.orElseThrow(() -> ApiException.notFound("staff.not_found"));
	}

	private UUID validCampus(UUID schoolId, UUID campusId) {
		if (campusId == null) {
			return null;
		}
		CampusRef campus = this.directory.campus(campusId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (campus == null) {
			throw ApiException.notFound("campus.not_found");
		}
		if (!"ACTIVE".equals(campus.status())) {
			throw ApiException.conflict("campus.archived");
		}
		return campusId;
	}

	private void notifyAdded(SchoolRef school, UUID staffId, ProvisionedUser person, StaffType type, String language) {
		this.notifications.notify(NotificationRequest.to("STAFF_ADDED", person.id())
			.school(school.id())
			.source("SCHOOL_STAFF", staffId)
			.path("/")
			.args(school.name(), staffTypeLabel(type))
			.build());
		if (person.created()) {
			Locale locale = Locale.forLanguageTag((person.preferredLanguageCode() != null) ? person.preferredLanguageCode() : language);
			String phone = person.phoneE164();
			String mail = person.email();
			AfterCommit.run(() -> this.executor.execute(() -> welcome(phone, mail, school.name(), locale)));
		}
	}

	private void welcome(String phone, String mail, String schoolName, Locale locale) {
		try {
			if (phone != null) {
				this.sms.send(phone, this.messages.getMessage("staff.welcome.sms", new Object[] { schoolName }, locale));
			}
			else if (mail != null) {
				this.email.send(mail, this.messages.getMessage("staff.welcome.email.subject", new Object[] { schoolName }, locale),
						this.messages.getMessage("staff.welcome.email.body", new Object[] { schoolName }, locale));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not deliver the staff welcome message: {}", ex.getMessage());
		}
	}

	/** The staff type in each supported language, resolved per recipient by the notification renderer. */
	private LocalizedText staffTypeLabel(StaffType type) {
		String key = "staff.type." + type.name().toLowerCase(Locale.ROOT);
		Map<String, String> labels = new HashMap<>();
		for (String language : this.languages.codes()) {
			labels.put(language, this.messages.getMessage(key, null, Locale.forLanguageTag(language)));
		}
		return LocalizedText.of(labels);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private static final String SELECT = """
			select ss.id, ss.user_id, ss.staff_type, ss.status, ss.job_title, ss.campus_id, ss.employee_number, ss.started_on,
			       ss.ended_on, u.display_name
			from school_staff ss join users u on u.id = ss.user_id""";

	private static StaffRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new StaffRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id"), StaffType.valueOf(rs.getString("staff_type")),
				StaffStatus.valueOf(rs.getString("status")), rs.getString("job_title"), Rows.uuid(rs, "campus_id"),
				rs.getString("employee_number"), Rows.date(rs, "started_on"), Rows.date(rs, "ended_on"),
				rs.getString("display_name"));
	}

	private record StaffRow(UUID id, UUID userId, StaffType staffType, StaffStatus status, String jobTitle, UUID campusId,
			String employeeNumber, LocalDate startedOn, LocalDate endedOn, String displayName) {
	}

	public enum StaffType {

		TEACHER, ADMINISTRATOR, SUPPORT_STAFF

	}

	public enum StaffStatus {

		INVITED, ACTIVE, SUSPENDED, FORMER

	}

	/** Administrative roles a school administrator may hand out while adding an ADMINISTRATOR. */
	public enum AdminRole {

		SCHOOL_ADMIN, CAMPUS_ADMIN, SCHOOL_STAFF

	}

	public record AddStaff(String phone, String email, String country, String givenName, String familyName,
			String displayName, StaffType staffType, String jobTitle, String employeeNumber, UUID campusId,
			String preferredLanguage, AdminRole adminRole) {
	}

	public record StaffView(@NotNull UUID id, @NotNull @Schema(description = "The person's user id (a teacher's id in class rosters)") UUID userId,
			@NotNull String displayName, MediaSummary avatar, @NotNull StaffType staffType, @NotNull StaffStatus status,
			String jobTitle, UUID campusId, LocalDate startedOn, LocalDate endedOn,
			@Schema(description = "Only for school.staff.manage") String employeeNumber,
			@Schema(description = "Masked; only for school.staff.manage") String maskedPhone,
			@Schema(description = "Masked; only for school.staff.manage") String maskedEmail) {
	}

}
