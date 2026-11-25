package com.nemaz.school.engagement.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.engagement.BadgeAwarded;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.notifications.LocalizedText;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Badge definitions (the school's copies of the platform templates and its own) and their grants.
 * <ul>
 * <li><b>Criteria:</b> {@code POINTS_TOTAL} {@code {"threshold": n, "context": "CLASS"|"SCHOOL"?}}, {@code CATEGORY_TOTAL}
 * {@code {"threshold": n, "categoryId"|"templateCode": ...}} (both over CLASS and SCHOOL points, HOME never counts),
 * {@code ATTENDANCE_STREAK} {@code {"days": n}} (granted by the attendance module through
 * {@code PointsApi#grantBadge}, M7), {@code MANUAL} (a teacher awards it).</li>
 * <li><b>Grants</b> are idempotent: one per student and badge ({@code unique (badge_id, student_id)}); only a new grant
 * publishes {@link BadgeAwarded} and notifies the student and their guardians.</li>
 * <li>{@code engagement.badges.enabled} (school) switches awarding off; definitions stay editable.</li>
 * </ul>
 */
@Service
@Transactional
public class BadgeService {

	private static final Set<String> TYPES = Set.of("POINTS_TOTAL", "CATEGORY_TOTAL", "ATTENDANCE_STREAK", "MANUAL");

	private final JdbcClient jdbc;

	private final EngagementAccess access;

	private final EngagementSeeding seeding;

	private final EngagementSettings settings;

	private final NotificationService notifications;

	private final ApplicationEventPublisher events;

	private final AuditLog audit;

	private final JsonMapper json;

	private final Clock clock;

	BadgeService(JdbcClient jdbc, EngagementAccess access, EngagementSeeding seeding, EngagementSettings settings,
			NotificationService notifications, ApplicationEventPublisher events, AuditLog audit, JsonMapper json,
			Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.seeding = seeding;
		this.settings = settings;
		this.notifications = notifications;
		this.events = events;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	// --- definitions ------------------------------------------------------------------------

	/** The school's badges: visible to school members; managers see inactive ones too. */
	public List<BadgeView> list(CurrentUser user, UUID schoolId, boolean includeInactive) {
		this.access.gate().requireVisible(user, schoolId);
		this.seeding.ensure(schoolId);
		boolean manager = this.access.pointsManager(user, schoolId);
		return this.jdbc.sql(SELECT + " where school_id = :s and (:all or is_active) order by sort_order, code")
			.param("s", schoolId)
			.param("all", includeInactive && manager)
			.query(this::row)
			.list()
			.stream()
			.map(this::view)
			.toList();
	}

	public BadgeView create(CurrentUser user, UUID schoolId, BadgeInput in) {
		this.access.gate().require(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
		this.seeding.ensure(schoolId);
		Map<String, Object> criteria = validateCriteria(schoolId, in.criteriaType(), in.criteria());
		boolean exists = this.jdbc.sql("select exists (select 1 from badges where school_id = :s and code = :c)")
			.param("s", schoolId)
			.param("c", in.code())
			.query(Boolean.class)
			.single();
		if (exists) {
			throw ApiException.conflict("points.badge_code_taken");
		}
		UUID id = Ids.newId();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				insert into badges (id, school_id, code, name_i18n, description_i18n, icon, criteria_type, criteria, sort_order,
				                    created_by, created_at, updated_at)
				values (:id, :s, :code, cast(:names as jsonb), cast(:desc as jsonb), :icon, :type, cast(:criteria as jsonb), :sort,
				        :by, :now, :now)""")
			.param("id", id)
			.param("s", schoolId)
			.param("code", in.code())
			.param("names", Names.write(this.json, clean(in.names())))
			.param("desc", (in.descriptions() == null || in.descriptions().isEmpty()) ? null
					: Names.write(this.json, in.descriptions()))
			.param("icon", in.icon())
			.param("type", in.criteriaType())
			.param("criteria", criteria.isEmpty() ? null : this.json.writeValueAsString(criteria))
			.param("sort", (in.sortOrder() != null) ? in.sortOrder() : 500)
			.param("by", user.userId())
			.param("now", now)
			.update();
		this.audit.record(AuditEntry.of("points.badge.create", AuditOutcome.SUCCESS)
			.target("badge", id)
			.school(schoolId)
			.metadata("criteriaType", in.criteriaType()));
		return view(find(schoolId, id).orElseThrow());
	}

	public BadgeView update(CurrentUser user, UUID schoolId, UUID badgeId, BadgePatch p) {
		this.access.gate().require(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
		Badge current = find(schoolId, badgeId).orElseThrow(() -> ApiException.notFound("points.badge_not_found"));
		List<String> fields = new ArrayList<>();
		if (p.names() != null) {
			this.jdbc.sql("update badges set name_i18n = cast(:v as jsonb) where id = :id")
				.param("v", Names.write(this.json, clean(p.names())))
				.param("id", badgeId)
				.update();
			fields.add("names");
		}
		if (p.descriptions() != null) {
			this.jdbc.sql("update badges set description_i18n = cast(:v as jsonb) where id = :id")
				.param("v", p.descriptions().isEmpty() ? null : Names.write(this.json, p.descriptions()))
				.param("id", badgeId)
				.update();
			fields.add("descriptions");
		}
		if (p.icon() != null) {
			this.jdbc.sql("update badges set icon = :v where id = :id").param("v", p.icon()).param("id", badgeId).update();
			fields.add("icon");
		}
		if (p.criteria() != null) {
			Map<String, Object> criteria = validateCriteria(schoolId, current.criteriaType(), p.criteria());
			this.jdbc.sql("update badges set criteria = cast(:v as jsonb) where id = :id")
				.param("v", criteria.isEmpty() ? null : this.json.writeValueAsString(criteria))
				.param("id", badgeId)
				.update();
			fields.add("criteria");
		}
		if (p.sortOrder() != null) {
			this.jdbc.sql("update badges set sort_order = :v where id = :id").param("v", p.sortOrder()).param("id", badgeId).update();
			fields.add("sortOrder");
		}
		if (p.active() != null) {
			this.jdbc.sql("update badges set is_active = :v where id = :id").param("v", p.active()).param("id", badgeId).update();
			fields.add("active");
		}
		this.jdbc.sql("update badges set updated_at = :now, version = version + 1 where id = :id")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", badgeId)
			.update();
		this.audit.record(AuditEntry.of("points.badge.update", AuditOutcome.SUCCESS)
			.target("badge", badgeId)
			.school(schoolId)
			.metadata("fields", fields));
		return view(find(schoolId, badgeId).orElseThrow());
	}

	// --- grants -----------------------------------------------------------------------------

	/** A teacher of the student (or a points manager) hands out a MANUAL badge. Repeating it changes nothing. */
	public StudentBadgeView award(CurrentUser user, UUID schoolId, UUID studentId, UUID badgeId) {
		boolean manager = this.access.pointsManager(user, schoolId);
		if (!this.access.activeStudentOfSchool(schoolId, studentId)) {
			this.access.gate().requireVisible(user, schoolId);
			throw ApiException.notFound("student.not_found");
		}
		if (!manager && !this.access.memberships().teachesStudent(user.userId(), studentId)) {
			throw this.access.studentDenied(user, schoolId, studentId);
		}
		this.access.requireFeature(schoolId);
		Badge badge = find(schoolId, badgeId).orElseThrow(() -> ApiException.notFound("points.badge_not_found"));
		if (!"MANUAL".equals(badge.criteriaType())) {
			throw ApiException.conflict("points.badge_not_manual");
		}
		if (!badge.active()) {
			throw ApiException.conflict("points.badge_inactive");
		}
		if (!this.settings.badges(schoolId)) {
			throw ApiException.conflict("points.badges_disabled");
		}
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("manual", true);
		boolean granted = grant(schoolId, studentId, badge, user.userId(), source);
		if (granted) {
			this.audit.record(AuditEntry.of("points.badge.award", AuditOutcome.SUCCESS)
				.target("badge", badgeId)
				.school(schoolId)
				.metadata("studentId", studentId.toString()));
		}
		return this.jdbc.sql(STUDENT_BADGES + " where sb.student_id = :st and sb.badge_id = :b")
			.param("st", studentId)
			.param("b", badgeId)
			.query(this::studentBadgeRow)
			.single();
	}

	/**
	 * Grants {@code badge} unless the student holds it; the first grant publishes {@link BadgeAwarded} and notifies.
	 * Joins the caller's transaction.
	 */
	boolean grant(UUID schoolId, UUID studentId, Badge badge, UUID awardedBy, Map<String, Object> source) {
		UUID id = Ids.newId();
		int inserted = this.jdbc.sql("""
				insert into student_badges (id, badge_id, student_id, school_id, awarded_at, awarded_by, source)
				values (:id, :badge, :student, :school, :now, :by, cast(:source as jsonb))
				on conflict (badge_id, student_id) do nothing""")
			.param("id", id)
			.param("badge", badge.id())
			.param("student", studentId)
			.param("school", schoolId)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("by", awardedBy)
			.param("source", (source == null || source.isEmpty()) ? null : this.json.writeValueAsString(source))
			.update();
		if (inserted == 0) {
			return false;
		}
		this.events.publishEvent(new BadgeAwarded(id, badge.id(), badge.code(), schoolId, studentId, awardedBy));
		notifyRecipients(id, schoolId, studentId, badge);
		return true;
	}

	private void notifyRecipients(UUID grantId, UUID schoolId, UUID studentId, Badge badge) {
		Set<UUID> recipients = new LinkedHashSet<>();
		recipients.add(studentId);
		for (GuardianGrant g : this.access.guardians().guardiansOf(studentId)) {
			if (g.allows(GuardianPermission.RECEIVE_NOTIFICATIONS)) {
				recipients.add(g.guardianId());
			}
		}
		recipients.removeIf((r) -> this.notifications.alreadyNotified(r, "BADGE_AWARDED", "student_badge", grantId));
		if (recipients.isEmpty()) {
			return;
		}
		String child = this.jdbc.sql("select given_name from users where id = :id")
			.param("id", studentId)
			.query(String.class)
			.optional()
			.orElse("");
		for (UUID recipient : recipients) {
			this.notifications.notify(NotificationRequest.to("BADGE_AWARDED", recipient)
				.args(child, LocalizedText.of(badge.names()))
				.school(schoolId)
				.source("student_badge", grantId)
				.path(recipient.equals(studentId) ? "/points" : "/children")
				.data("studentId", studentId.toString())
				.build());
		}
	}

	/** The badges a student holds (revoked ones left out), newest first. */
	List<StudentBadgeView> badgesOf(UUID schoolId, UUID studentId) {
		return this.jdbc.sql(STUDENT_BADGES + """
				 where sb.student_id = :st and sb.school_id = :s and sb.revoked_at is null
				order by sb.awarded_at desc, sb.id desc""")
			.param("st", studentId)
			.param("s", schoolId)
			.query(this::studentBadgeRow)
			.list();
	}

	private static final String STUDENT_BADGES = """
			select b.id, b.code, b.name_i18n, b.description_i18n, b.icon, sb.awarded_at
			from student_badges sb join badges b on b.id = sb.badge_id""";

	private StudentBadgeView studentBadgeRow(ResultSet rs, int i) throws SQLException {
		Locale locale = LocaleContextHolder.getLocale();
		return new StudentBadgeView(Rows.uuid(rs, "id"), rs.getString("code"),
				Names.pick(Names.read(this.json, rs.getString("name_i18n")), locale),
				Names.pick(Names.read(this.json, rs.getString("description_i18n")), locale), rs.getString("icon"),
				Rows.instant(rs, "awarded_at"));
	}

	// --- lookups ----------------------------------------------------------------------------

	Optional<Badge> find(UUID schoolId, UUID badgeId) {
		return this.jdbc.sql(SELECT + " where id = :id and school_id = :s")
			.param("id", badgeId)
			.param("s", schoolId)
			.query(this::row)
			.optional();
	}

	Optional<Badge> findByCode(UUID schoolId, String code) {
		this.seeding.ensure(schoolId);
		return this.jdbc.sql(SELECT + " where school_id = :s and code = :c")
			.param("s", schoolId)
			.param("c", code)
			.query(this::row)
			.optional();
	}

	/** Active, rule-driven badges of the school the student does not hold yet. */
	List<Badge> candidates(UUID schoolId, UUID studentId) {
		return this.jdbc.sql(SELECT + """
				 where school_id = :s and is_active and criteria_type in ('POINTS_TOTAL', 'CATEGORY_TOTAL')
				  and not exists (select 1 from student_badges sb where sb.badge_id = badges.id and sb.student_id = :st)
				order by sort_order, code""")
			.param("s", schoolId)
			.param("st", studentId)
			.query(this::row)
			.list();
	}

	private Map<String, Object> validateCriteria(UUID schoolId, String type, Map<String, Object> criteria) {
		if (type == null || !TYPES.contains(type)) {
			throw ApiException.badRequest("points.invalid_badge");
		}
		Map<String, Object> clean = new LinkedHashMap<>();
		switch (type) {
			case "MANUAL" -> {
				if (criteria != null && !criteria.isEmpty()) {
					throw ApiException.badRequest("points.invalid_badge");
				}
			}
			case "POINTS_TOTAL" -> {
				clean.put("threshold", intIn(criteria, "threshold", 1, 100_000));
				Object context = (criteria != null) ? criteria.get("context") : null;
				if (context != null) {
					if (!"CLASS".equals(context) && !"SCHOOL".equals(context)) {
						throw ApiException.badRequest("points.invalid_badge");
					}
					clean.put("context", context);
				}
			}
			case "CATEGORY_TOTAL" -> {
				clean.put("threshold", intIn(criteria, "threshold", 1, 100_000));
				Object categoryId = (criteria != null) ? criteria.get("categoryId") : null;
				Object templateCode = (criteria != null) ? criteria.get("templateCode") : null;
				if ((categoryId == null) == (templateCode == null)) {
					throw ApiException.badRequest("points.invalid_badge");
				}
				if (categoryId != null) {
					UUID id;
					try {
						id = UUID.fromString(categoryId.toString());
					}
					catch (IllegalArgumentException ex) {
						throw ApiException.badRequest("points.invalid_badge");
					}
					boolean known = this.jdbc.sql("select exists (select 1 from point_categories where id = :id and school_id = :s and context = 'CLASS')")
						.param("id", id)
						.param("s", schoolId)
						.query(Boolean.class)
						.single();
					if (!known) {
						throw ApiException.notFound("points.category_not_found");
					}
					clean.put("categoryId", id.toString());
				}
				else {
					clean.put("templateCode", templateCode.toString());
				}
			}
			default -> clean.put("days", intIn(criteria, "days", 1, 365));
		}
		return clean;
	}

	private static int intIn(Map<String, Object> criteria, String key, int min, int max) {
		Object value = (criteria != null) ? criteria.get(key) : null;
		if (!(value instanceof Number n) || n.intValue() < min || n.intValue() > max) {
			throw ApiException.badRequest("points.invalid_badge");
		}
		return n.intValue();
	}

	private static Map<String, String> clean(Map<String, String> names) {
		Map<String, String> clean = new LinkedHashMap<>();
		names.forEach((language, text) -> {
			if (language != null && language.matches("^[a-z]{2,3}$") && text != null && !text.isBlank()) {
				clean.put(language, text.strip());
			}
		});
		if (clean.isEmpty()) {
			throw ApiException.badRequest("points.invalid_badge");
		}
		return clean;
	}

	private static final String SELECT = """
			select id, school_id, code, name_i18n, description_i18n, icon, criteria_type, criteria, is_active, sort_order
			from badges""";

	@SuppressWarnings("unchecked")
	private Badge row(ResultSet rs, int i) throws SQLException {
		String criteria = rs.getString("criteria");
		return new Badge(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), rs.getString("code"),
				Names.read(this.json, rs.getString("name_i18n")), Names.read(this.json, rs.getString("description_i18n")),
				rs.getString("icon"), rs.getString("criteria_type"),
				(criteria == null) ? Map.of() : this.json.readValue(criteria, Map.class), rs.getBoolean("is_active"),
				rs.getInt("sort_order"));
	}

	private BadgeView view(Badge b) {
		Locale locale = LocaleContextHolder.getLocale();
		return new BadgeView(b.id(), b.code(), Names.pick(b.names(), locale), b.names(), Names.pick(b.descriptions(), locale),
				b.descriptions(), b.icon(), b.criteriaType(), b.criteria(), b.active(), b.sortOrder());
	}

	record Badge(UUID id, UUID schoolId, String code, Map<String, String> names, Map<String, String> descriptions,
			String icon, String criteriaType, Map<String, Object> criteria, boolean active, int sortOrder) {
	}

	public record BadgeView(@NotNull UUID id, @NotNull String code, @NotNull String name,
			@NotNull Map<String, String> names, String description, @NotNull Map<String, String> descriptions, String icon,
			@NotNull String criteriaType, @Schema(description = "Rule parameters: threshold, context, categoryId or templateCode, days") @NotNull Map<String, Object> criteria,
			boolean active, int sortOrder) {
	}

	public record StudentBadgeView(@NotNull UUID badgeId, @NotNull String code, @NotNull String name, String description,
			String icon, @NotNull Instant awardedAt) {
	}

	public record BadgeInput(String code, Map<String, String> names, Map<String, String> descriptions, String icon,
			String criteriaType, Map<String, Object> criteria, Integer sortOrder) {
	}

	public record BadgePatch(Map<String, String> names, Map<String, String> descriptions, String icon,
			Map<String, Object> criteria, Integer sortOrder, Boolean active) {
	}

}
