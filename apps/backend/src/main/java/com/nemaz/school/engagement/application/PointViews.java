package com.nemaz.school.engagement.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.DataAccessLevel;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.engagement.PointTransactionType;
import com.nemaz.school.engagement.application.BadgeService.StudentBadgeView;
import com.nemaz.school.engagement.application.PointsLedger.AwarderRole;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.media.MediaSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of points, with strict privacy (spec §100: nothing is filtered on the client).
 * <ul>
 * <li><b>Class overview:</b> each ACTIVE student with their class total, for the class's ACTIVE teachers and managers
 * only; guardians and students get 404/403, never another child's points.</li>
 * <li><b>Leaderboard:</b> only while {@code engagement.leaderboard.enabled} is on (default off), for members of the
 * class roster, top positive totals only.</li>
 * <li><b>A student's points:</b> the student themself, their ACTIVE unrestricted guardians with
 * {@code can_view_academics}, their class teachers (the points of the classes they teach) and holders of
 * {@code school.points.read}. The result has two separate sections: the school's (CLASS and SCHOOL points) and the
 * home one (guardian awards), which only the student and their guardians ever see and which is never added into
 * class totals (D2).</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PointViews {

	private final JdbcClient jdbc;

	private final EngagementAccess access;

	private final EngagementSettings settings;

	private final BadgeService badges;

	private final JsonMapper json;

	PointViews(JdbcClient jdbc, EngagementAccess access, EngagementSettings settings, BadgeService badges,
			JsonMapper json) {
		this.jdbc = jdbc;
		this.access = access;
		this.settings = settings;
		this.badges = badges;
		this.json = json;
	}

	// --- class ------------------------------------------------------------------------------

	public ClassPoints classPoints(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.access.teachableClass(user, schoolId, classId);
		Set<UUID> students = this.access.memberships().activeStudentIds(classId);
		Map<UUID, int[]> totals = classTotals(classId, ref.academicYearId(), students);
		Map<UUID, PersonCard> cards = this.access.people().cards(students);
		List<StudentTotal> rows = new ArrayList<>();
		int positive = 0;
		int needsWork = 0;
		for (UUID id : students) {
			int[] t = totals.getOrDefault(id, new int[2]);
			PersonCard card = cards.get(id);
			rows.add(new StudentTotal(id, (card != null) ? card.displayName() : "", (card != null) ? card.avatar() : null,
					(card != null) ? card.avatarConfig() : null, DataAccessLevel.BASIC, t[0], t[1], t[0] + t[1]));
			positive += t[0];
			needsWork += t[1];
		}
		rows.sort(Comparator.comparing((StudentTotal s) -> s.displayName().toLowerCase(Locale.ROOT)).thenComparing(StudentTotal::studentId));
		return new ClassPoints(classId, ref.academicYearId(), rows, positive, needsWork, positive + needsWork,
				new EngagementSettingsView(this.settings.needsWork(schoolId), this.settings.leaderboard(schoolId),
						this.settings.badges(schoolId), this.settings.undoWindowSeconds(schoolId)));
	}

	public Leaderboard leaderboard(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.access.classes().find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.access.classes().canViewRoster(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		if (!this.settings.leaderboard(schoolId)) {
			throw ApiException.forbidden("points.leaderboard_disabled");
		}
		Set<UUID> students = this.access.memberships().activeStudentIds(classId);
		Map<UUID, int[]> totals = classTotals(classId, ref.academicYearId(), students);
		List<Map.Entry<UUID, Integer>> ranked = totals.entrySet()
			.stream()
			.map((e) -> Map.entry(e.getKey(), e.getValue()[0] + e.getValue()[1]))
			.filter((e) -> e.getValue() > 0 && students.contains(e.getKey()))
			.sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(10)
			.toList();
		Map<UUID, PersonCard> cards = this.access.people().cards(ranked.stream().map(Map.Entry::getKey).toList());
		List<LeaderboardRow> rows = new ArrayList<>();
		int rank = 0;
		Integer previous = null;
		int position = 0;
		for (Map.Entry<UUID, Integer> e : ranked) {
			position++;
			if (!e.getValue().equals(previous)) {
				rank = position;
				previous = e.getValue();
			}
			PersonCard card = cards.get(e.getKey());
			rows.add(new LeaderboardRow(rank, e.getKey(), (card != null) ? card.displayName() : "",
					(card != null) ? card.avatar() : null, e.getValue()));
		}
		return new Leaderboard(classId, rows);
	}

	private Map<UUID, int[]> classTotals(UUID classId, UUID yearId, Set<UUID> students) {
		Map<UUID, int[]> totals = new HashMap<>();
		if (students.isEmpty()) {
			return totals;
		}
		this.jdbc.sql("""
				select student_id, positive_total, needs_work_total from point_balances
				where class_id = :c and context = 'CLASS' and academic_year_id = :y and student_id in (:ids)""")
			.param("c", classId)
			.param("y", yearId)
			.param("ids", students)
			.query((rs, i) -> {
				totals.put(Rows.uuid(rs, "student_id"), new int[] { rs.getInt("positive_total"), rs.getInt("needs_work_total") });
				return 0;
			})
			.list();
		return totals;
	}

	// --- one student ------------------------------------------------------------------------

	public StudentPoints studentPoints(CurrentUser user, UUID schoolId, UUID studentId, UUID academicYearId) {
		Viewer viewer = viewer(user, schoolId, studentId);
		UUID year = (academicYearId != null) ? academicYearId : this.access.currentYear(schoolId).orElse(null);
		Section school = section(schoolId, studentId, year, viewer, false);
		Section home = (viewer.home()) ? section(schoolId, studentId, year, viewer, true) : null;
		PersonCard card = this.access.people().cards(List.of(studentId)).get(studentId);
		List<StudentBadgeView> earned = this.badges.badgesOf(schoolId, studentId);
		return new StudentPoints(studentId, (card != null) ? card.displayName() : "", (card != null) ? card.avatar() : null,
				(card != null) ? card.avatarConfig() : null, DataAccessLevel.BASIC, year, school, home, earned);
	}

	/** The timeline of one section, newest first. {@code section} HOME needs the student or an ACTIVE guardian. */
	public CursorPage<TimelineItem> timeline(CurrentUser user, UUID schoolId, UUID studentId, PointSection section,
			UUID academicYearId, String cursor, Integer limit) {
		Viewer viewer = viewer(user, schoolId, studentId);
		boolean home = section == PointSection.HOME;
		if (home && !viewer.home()) {
			throw ApiException.forbidden("points.access_denied");
		}
		int pageSize = Cursors.clampLimit(limit);
		UUID after = null;
		if (cursor != null) {
			try {
				after = UUID.fromString(Cursors.decode(cursor, 1).getFirst());
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		Restriction r = restriction(viewer, home, "t");
		Locale locale = LocaleContextHolder.getLocale();
		List<TimelineItem> rows = this.jdbc.sql("""
				select t.id, t.created_at, t.context, t.class_id, cl.name as class_name, t.category_id, pc.name_i18n, pc.icon, pc.color,
				       t.polarity, t.points, t.transaction_type, t.reverses_transaction_id, t.awarder_role, t.reason,
				       u.display_name as awarded_by_name,
				       exists (select 1 from point_transactions r where r.reverses_transaction_id = t.id) as reversed
				from point_transactions t
				join point_categories pc on pc.id = t.category_id
				left join classes cl on cl.id = t.class_id
				left join users u on u.id = t.awarded_by
				where t.student_id = :st and t.school_id = :s and (""" + r.sql() + """
				) and (cast(:year as uuid) is null or t.academic_year_id = :year)
				  and (cast(:after as uuid) is null or t.id < :after)
				order by t.id desc limit :n""")
			.param("st", studentId)
			.param("s", schoolId)
			.param("year", academicYearId)
			.param("after", after)
			.param("n", pageSize + 1)
			.params(r.params())
			.query((rs, i) -> new TimelineItem(Rows.uuid(rs, "id"), Rows.instant(rs, "created_at"),
					PointContext.valueOf(rs.getString("context")), Rows.uuid(rs, "class_id"), rs.getString("class_name"),
					Rows.uuid(rs, "category_id"), Names.pick(Names.read(this.json, rs.getString("name_i18n")), locale),
					rs.getString("icon"), rs.getString("color"), PointPolarity.valueOf(rs.getString("polarity")),
					rs.getInt("points"), PointTransactionType.valueOf(rs.getString("transaction_type")),
					Rows.uuid(rs, "reverses_transaction_id"), rs.getBoolean("reversed"),
					AwarderRole.valueOf(rs.getString("awarder_role")).name(), rs.getString("awarded_by_name"),
					rs.getString("reason")))
			.list();
		return CursorPage.fromOverfetch(rows, pageSize, (row) -> Cursors.encode(row.id().toString()));
	}

	private Section section(UUID schoolId, UUID studentId, UUID year, Viewer viewer, boolean home) {
		Restriction r = restriction(viewer, home, "t");
		Locale locale = LocaleContextHolder.getLocale();
		List<CategoryTotal> categories = this.jdbc.sql("""
				select t.category_id, pc.name_i18n, pc.icon, pc.color, t.polarity, sum(t.points) as points,
				       count(*) filter (where t.transaction_type = 'AWARD') as awards
				from point_transactions t join point_categories pc on pc.id = t.category_id
				where t.student_id = :st and t.school_id = :s and (""" + r.sql() + """
				) and (cast(:year as uuid) is null or t.academic_year_id = :year)
				group by t.category_id, pc.name_i18n, pc.icon, pc.color, t.polarity
				order by sum(t.points) desc, t.category_id""")
			.param("st", studentId)
			.param("s", schoolId)
			.param("year", year)
			.params(r.params())
			.query((rs, i) -> new CategoryTotal(Rows.uuid(rs, "category_id"),
					Names.pick(Names.read(this.json, rs.getString("name_i18n")), locale), rs.getString("icon"),
					rs.getString("color"), PointPolarity.valueOf(rs.getString("polarity")), rs.getInt("points"),
					rs.getInt("awards")))
			.list();
		int positive = categories.stream().filter((c) -> c.polarity() == PointPolarity.POSITIVE).mapToInt(CategoryTotal::points).sum();
		int needsWork = categories.stream().filter((c) -> c.polarity() == PointPolarity.NEEDS_WORK).mapToInt(CategoryTotal::points).sum();
		return new Section(positive + needsWork, positive, needsWork, categories);
	}

	/** Which ledger rows the viewer may see in a section (SQL over the alias, with its own parameter names). */
	private Restriction restriction(Viewer viewer, boolean home, String a) {
		if (home) {
			return new Restriction(a + ".context = 'HOME'", Map.of());
		}
		if (viewer.fullSchool()) {
			return new Restriction(a + ".context in ('CLASS', 'SCHOOL')", Map.of());
		}
		if (viewer.teachableClasses().isEmpty()) {
			return new Restriction("false", Map.of());
		}
		return new Restriction(a + ".context = 'CLASS' and " + a + ".class_id in (:vw_classes)",
				Map.of("vw_classes", viewer.teachableClasses()));
	}

	/**
	 * Who the caller is for this student, or the 404/403 for a caller with no business here. Teachers see the CLASS
	 * points of the classes they teach; the student, ACTIVE guardians with {@code can_view_academics} and
	 * {@code school.points.read} holders see everything of the school section; only the student and guardians see HOME.
	 */
	private Viewer viewer(CurrentUser user, UUID schoolId, UUID studentId) {
		if (!this.access.knownStudent(schoolId, studentId)) {
			throw this.access.studentDenied(user, schoolId, studentId);
		}
		boolean self = user.userId().equals(studentId);
		boolean guardian = this.access.guardians().can(user.userId(), studentId, GuardianPermission.VIEW_ACADEMICS);
		boolean reader = this.access.pointsReader(user, schoolId);
		Set<UUID> classes = new LinkedHashSet<>();
		if (!self && !guardian && !reader) {
			List<UUID> candidates = this.jdbc.sql("select distinct class_id from point_transactions where student_id = :st and school_id = :s and class_id is not null")
				.param("st", studentId)
				.param("s", schoolId)
				.query(UUID.class)
				.list();
			for (UUID classId : candidates) {
				if (this.access.classes().canTeach(user, classId)) {
					classes.add(classId);
				}
			}
			if (classes.isEmpty() && !this.access.memberships().teachesStudent(user.userId(), studentId)) {
				throw this.access.studentDenied(user, schoolId, studentId);
			}
		}
		return new Viewer(self || guardian || reader, self || guardian, classes);
	}

	private record Viewer(boolean fullSchool, boolean home, Set<UUID> teachableClasses) {
	}

	private record Restriction(String sql, Map<String, Object> params) {
	}

	/** Which part of a child's points: the school's (class and school-wide) or the family's home points. */
	public enum PointSection {

		SCHOOL, HOME

	}

	// --- shapes -----------------------------------------------------------------------------

	public record EngagementSettingsView(boolean needsWorkEnabled, boolean leaderboardEnabled, boolean badgesEnabled,
			@Schema(description = "Seconds in which the awarder may undo an award; 0 when the quick undo is off") int undoWindowSeconds) {
	}

	public record StudentTotal(@NotNull UUID studentId, @NotNull String displayName, MediaSummary avatar,
			@Schema(description = "Avatar builder configuration, client-defined") Map<String, Object> avatarConfig,
			@NotNull DataAccessLevel accessLevel, int positiveTotal, int needsWorkTotal,
			@Schema(description = "positiveTotal + needsWorkTotal (needs-work points are negative)") int total) {
	}

	public record ClassPoints(@NotNull UUID classId, @NotNull UUID academicYearId, @NotNull List<StudentTotal> students,
			int positiveTotal, int needsWorkTotal, int total, @NotNull EngagementSettingsView settings) {
	}

	public record LeaderboardRow(int rank, @NotNull UUID studentId, @NotNull String displayName, MediaSummary avatar,
			int total) {
	}

	public record Leaderboard(@NotNull UUID classId, @NotNull List<LeaderboardRow> rows) {
	}

	public record CategoryTotal(@NotNull UUID categoryId, @NotNull String name, String icon, String color,
			@NotNull PointPolarity polarity, int points, int awards) {
	}

	public record Section(int total, int positiveTotal, int needsWorkTotal, @NotNull List<CategoryTotal> categories) {
	}

	public record StudentPoints(@NotNull UUID studentId, @NotNull String displayName, MediaSummary avatar,
			@Schema(description = "Avatar builder configuration, client-defined") Map<String, Object> avatarConfig,
			@NotNull DataAccessLevel accessLevel,
			@Schema(description = "The year the totals cover (the school's current year by default); absent when it has none") UUID academicYearId,
			@Schema(description = "CLASS and SCHOOL points the caller may see") @NotNull Section school,
			@Schema(description = "Home points: only for the student and their guardians; never part of class or school totals") Section home,
			@NotNull List<StudentBadgeView> badges) {
	}

	public record TimelineItem(@NotNull UUID id, @NotNull Instant createdAt, @NotNull PointContext context, UUID classId,
			String className, @NotNull UUID categoryId, @NotNull String categoryName, String icon, String color,
			@NotNull PointPolarity polarity, int points, @NotNull PointTransactionType type, UUID reversesTransactionId,
			@Schema(description = "True when a later REVERSAL row undid this award") boolean reversed,
			@NotNull String awarderRole, String awardedByName,
			@Schema(description = "The awarder's note (child data: only where the caller may see this row)") String note) {
	}

}
