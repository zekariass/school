package com.nemaz.school.portfolio.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one SQL definition of "may this user see this portfolio item" (rule book P4), used by the single read, every list and
 * gallery and the media policy, so they can never disagree (spec §100: nothing is filtered on the client). Nothing is cached:
 * links, memberships, the school cap and the school's status are read on every request.
 *
 * <p>
 * A non-deleted item is visible to:
 * <ul>
 * <li>the <b>oversight</b> holders of {@code school.portfolios.review} over its school (any status except DRAFT), also in a school
 * that is not ACTIVE (safeguarding);</li>
 * <li>while the school is ACTIVE: the <b>student</b> (ACTIVE in the school), in every status; the item's <b>reviewers</b> (ACTIVE
 * LEAD or CO_TEACHER of the item's class, a SUBJECT_TEACHER whose subject is the item's, a class manager), any status except DRAFT;</li>
 * <li>for APPROVED items only, by <em>effective</em> visibility (the lower of the item's and the school cap): GUARDIANS and up
 * the student's own guardians (ACTIVE link, not custody-restricted, {@code can_view_academics}, the student ACTIVE in the
 * school); CLASS and up the ACTIVE students and teachers of the item's class while the student is still ACTIVE in it; SCHOOL the
 * ACTIVE students and staff of the school.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class PortfolioAudience {

	private final ClassAccess classes;

	private final AccessControl access;

	private final PortfolioSettings settings;

	private final JdbcClient jdbc;

	PortfolioAudience(ClassAccess classes, AccessControl access, PortfolioSettings settings, JdbcClient jdbc) {
		this.classes = classes;
		this.access = access;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	private static final String RANK = "case %s.visibility when 'STUDENT_TEACHER' then 0 when 'GUARDIANS' then 1 when 'CLASS' then 2 else 3 end";

	/** The effective visibility rank (0 to 3) of the rows of {@code portfolio_items} {@code i} in the given schools. */
	SqlFragment effectiveRank(Collection<UUID> schoolIds, String i) {
		Map<String, Object> params = new HashMap<>();
		StringBuilder cap = new StringBuilder("case ").append(i).append(".school_id");
		int n = 0;
		for (UUID school : new LinkedHashSet<>(schoolIds)) {
			cap.append(" when :pa_cs").append(n).append(" then :pa_cr").append(n);
			params.put("pa_cs" + n, school);
			params.put("pa_cr" + n, this.settings.maxVisibility(school).rank());
			n++;
		}
		cap.append(" else 0 end");
		return new SqlFragment("least(" + RANK.formatted(i) + ", " + cap + ")", params);
	}

	/** True for the rows {@code i} of schools where the user holds {@code school.portfolios.review}. */
	SqlFragment oversight(CurrentUser user, String i) {
		AccessibleScopes scopes = this.access.accessible(user, Permissions.SCHOOL_PORTFOLIOS_REVIEW);
		if (scopes.platform()) {
			return SqlFragment.always();
		}
		Map<String, Object> params = new HashMap<>();
		List<String> any = new ArrayList<>();
		if (!scopes.schoolIds().isEmpty()) {
			any.add(i + ".school_id in (:pa_os_schools)");
			params.put("pa_os_schools", scopes.schoolIds());
		}
		if (!scopes.organizationIds().isEmpty()) {
			any.add(i + ".school_id in (select pa_o.id from schools pa_o where pa_o.organization_id in (:pa_os_orgs))");
			params.put("pa_os_orgs", scopes.organizationIds());
		}
		if (!scopes.countryCodes().isEmpty()) {
			any.add(i + ".school_id in (select pa_k.id from schools pa_k where pa_k.country_code in (:pa_os_countries))");
			params.put("pa_os_countries", scopes.countryCodes());
		}
		return any.isEmpty() ? SqlFragment.never() : new SqlFragment("(" + String.join(" or ", any) + ")", params);
	}

	/** Class teachers who review (LEAD, CO_TEACHER, a matching SUBJECT_TEACHER) and class managers. */
	SqlFragment classReviewers(CurrentUser user, String i) {
		SqlFragment managed = this.classes.manageableClasses(user, "pa_mc");
		Map<String, Object> params = new HashMap<>(managed.params());
		params.put("pa_user", user.userId());
		String sql = "(" + i + ".class_id is not null and (exists (select 1 from class_teachers pa_ct where pa_ct.class_id = " + i
				+ ".class_id and pa_ct.teacher_id = :pa_user and pa_ct.status = 'ACTIVE' and (pa_ct.role in ('LEAD', 'CO_TEACHER')"
				+ " or (pa_ct.role = 'SUBJECT_TEACHER' and " + i + ".subject_id is not null and pa_ct.subject_id = " + i
				+ ".subject_id))) or exists (select 1 from classes pa_mc where pa_mc.id = " + i + ".class_id and ("
				+ managed.sql() + "))))";
		return new SqlFragment(sql, params);
	}

	private static String schoolActive(String i) {
		return "exists (select 1 from schools pa_sc where pa_sc.id = " + i + ".school_id and pa_sc.status = 'ACTIVE')";
	}

	/** Reviewers of the rows {@code i} (any status; the caller excludes DRAFT where it matters). */
	SqlFragment reviewers(CurrentUser user, String i) {
		SqlFragment oversight = oversight(user, i);
		SqlFragment teachers = classReviewers(user, i);
		Map<String, Object> params = new HashMap<>(oversight.params());
		params.putAll(teachers.params());
		return new SqlFragment("(" + oversight.sql() + " or (" + schoolActive(i) + " and " + teachers.sql() + "))", params);
	}

	/** True for the rows {@code i} the user may see, for the schools in {@code schoolIds} (the school cap is read for each). */
	SqlFragment viewable(CurrentUser user, String i, Collection<UUID> schoolIds) {
		SqlFragment oversight = oversight(user, i);
		SqlFragment teachers = classReviewers(user, i);
		SqlFragment rank = effectiveRank(schoolIds, i);
		SqlFragment roster = this.classes.rosterVisibleClasses(user, "pa_rc");
		Map<String, Object> params = new HashMap<>();
		params.putAll(oversight.params());
		params.putAll(teachers.params());
		params.putAll(rank.params());
		params.putAll(roster.params());
		params.put("pa_user", user.userId());
		String self = "(" + i + ".student_id = :pa_user and exists (select 1 from school_students pa_s where pa_s.school_id = " + i
				+ ".school_id and pa_s.student_id = :pa_user and pa_s.status = 'ACTIVE'))";
		String guardian = "(" + rank.sql() + " >= 1 and exists (select 1 from student_guardians pa_g join school_students pa_gs"
				+ " on pa_gs.student_id = pa_g.student_id and pa_gs.status = 'ACTIVE' and pa_gs.school_id = " + i + ".school_id"
				+ " where pa_g.guardian_id = :pa_user and pa_g.student_id = " + i + ".student_id and pa_g.status = 'ACTIVE'"
				+ " and not pa_g.custody_restricted and pa_g.can_view_academics))";
		String inClass = "(" + rank.sql() + " >= 2 and " + i + ".class_id is not null and exists (select 1 from classes pa_rc where"
				+ " pa_rc.id = " + i + ".class_id and (" + roster.sql() + ")) and exists (select 1 from class_students pa_cs where"
				+ " pa_cs.class_id = " + i + ".class_id and pa_cs.student_id = " + i + ".student_id and pa_cs.status = 'ACTIVE'))";
		String inSchool = "(" + rank.sql() + " >= 3 and (exists (select 1 from school_students pa_ms where pa_ms.school_id = " + i
				+ ".school_id and pa_ms.student_id = :pa_user and pa_ms.status = 'ACTIVE') or exists (select 1 from school_staff"
				+ " pa_mt where pa_mt.school_id = " + i + ".school_id and pa_mt.user_id = :pa_user and pa_mt.status = 'ACTIVE')))";
		String sql = i + ".deleted_at is null and ((" + i + ".status <> 'DRAFT' and " + oversight.sql() + ") or (" + schoolActive(i)
				+ " and (" + self + " or (" + i + ".status <> 'DRAFT' and " + teachers.sql() + ") or (" + i
				+ ".status = 'APPROVED' and (" + guardian + " or " + inClass + " or " + inSchool + ")))))";
		return new SqlFragment(sql, params);
	}

	/** The item if it exists, is not deleted and the user may see it; empty otherwise (answer 404). */
	public Optional<ItemRow> find(CurrentUser user, UUID itemId) {
		Optional<ItemRow> raw = raw(itemId);
		if (raw.isEmpty()) {
			return Optional.empty();
		}
		SqlFragment visible = viewable(user, "i", List.of(raw.get().schoolId()));
		return this.jdbc.sql("select " + PortfolioRows.ITEM_COLUMNS + " from portfolio_items i where i.id = :pa_item and ("
				+ visible.sql() + ")")
			.param("pa_item", itemId)
			.params(visible.params())
			.query(PortfolioRows::item)
			.optional();
	}

	/** The item whatever the caller may see (not deleted); callers authorize. */
	Optional<ItemRow> raw(UUID itemId) {
		return this.jdbc.sql("select " + PortfolioRows.ITEM_COLUMNS + " from portfolio_items i where i.id = :id and i.deleted_at is null")
			.param("id", itemId)
			.query(PortfolioRows::item)
			.optional();
	}

	/** Whether the user reviews the item (oversight, class reviewer or manager). */
	public boolean reviews(CurrentUser user, UUID itemId) {
		SqlFragment reviewers = reviewers(user, "i");
		return this.jdbc.sql("select exists (select 1 from portfolio_items i where i.id = :pa_item and (" + reviewers.sql() + "))")
			.param("pa_item", itemId)
			.params(reviewers.params())
			.query(Boolean.class)
			.single();
	}

	/** Which of the items the user reviews. */
	Set<UUID> reviewedAmong(CurrentUser user, Collection<UUID> itemIds) {
		if (itemIds.isEmpty()) {
			return Set.of();
		}
		SqlFragment reviewers = reviewers(user, "i");
		return new LinkedHashSet<>(this.jdbc
			.sql("select i.id from portfolio_items i where i.id in (:pa_ids) and (" + reviewers.sql() + ")")
			.param("pa_ids", itemIds)
			.params(reviewers.params())
			.query(UUID.class)
			.list());
	}

	/** Whether the user sees the item only through {@code school.portfolios.review} (not as the student, a class teacher or manager). */
	boolean oversightOnly(CurrentUser user, UUID itemId) {
		SqlFragment oversight = oversight(user, "i");
		SqlFragment teachers = classReviewers(user, "i");
		Map<String, Object> params = new HashMap<>(oversight.params());
		params.putAll(teachers.params());
		return this.jdbc.sql("select exists (select 1 from portfolio_items i where i.id = :pa_item and " + oversight.sql()
				+ " and not (" + teachers.sql() + ") and i.student_id <> :pa_user)")
			.param("pa_item", itemId)
			.param("pa_user", user.userId())
			.params(params)
			.query(Boolean.class)
			.single();
	}

}
