package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessibleScopes;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one SQL definition of "may this user see this published post or story", used by every feed list and by the media
 * policy, so a list and a single read can never disagree (spec §100: nothing is filtered on the client).
 * <ul>
 * <li><b>Class content</b>: the class is viewable through {@link ClassAccess#visibleClasses}, which is where the
 * guardian rule applies: an ACTIVE, not custody-restricted link with {@code can_view_academics} to a student ACTIVE in
 * the class. FORMER members and revoked, pending or restricted guardians see nothing.</li>
 * <li><b>School content</b>: an ACTIVE student or ACTIVE staff member of the school; a guardian under the same
 * conditions as above to a student ACTIVE in the school ({@code classes.former_member_history_access} is not applied:
 * history stays closed to former members); holders of {@code school.feed.manage} covering the school.</li>
 * <li>Only PUBLISHED, not deleted posts (and stories not past their expiry at the request's time).</li>
 * </ul>
 * Whether the media of a visible post is shown is a separate question (consent), answered per request by
 * {@link ConsentWithholding}.
 */
@Component
@Transactional(readOnly = true)
class FeedVisibility {

	private final ClassAccess classes;

	private final AccessControl access;

	private final JdbcClient jdbc;

	private final Clock clock;

	FeedVisibility(ClassAccess classes, AccessControl access, JdbcClient jdbc, Clock clock) {
		this.classes = classes;
		this.access = access;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** True for published, not deleted posts of {@code p} that the user may see. */
	SqlFragment visiblePosts(CurrentUser user, String p) {
		SqlFragment visible = visibleContent(user, p);
		return new SqlFragment(p + ".status = 'PUBLISHED' and " + p + ".deleted_at is null and (" + visible.sql() + ")",
				visible.params());
	}

	/** True for stories of {@code s} that are PUBLISHED, not expired now and visible to the user. */
	SqlFragment visibleStories(CurrentUser user, String s) {
		SqlFragment visible = visibleContent(user, s);
		Map<String, Object> params = new HashMap<>(visible.params());
		params.put("fv_now", Timestamp.from(this.clock.instant()));
		return new SqlFragment(s + ".status = 'PUBLISHED' and " + s + ".deleted_at is null and " + s
				+ ".expires_at > :fv_now and (" + visible.sql() + ")", params);
	}

	/** The scope test shared by posts and stories (both have scope_type, school_id and class_id). */
	private SqlFragment visibleContent(CurrentUser user, String a) {
		SqlFragment inClass = this.classes.visibleClasses(user, "fv_c");
		SqlFragment inSchool = schoolMember(user, a + ".school_id");
		Map<String, Object> params = new HashMap<>(inClass.params());
		params.putAll(inSchool.params());
		String sql = "((" + a + ".scope_type = 'CLASS' and exists (select 1 from classes fv_c where fv_c.id = " + a
				+ ".class_id and (" + inClass.sql() + "))) or (" + a + ".scope_type = 'SCHOOL' and (" + inSchool.sql() + ")))";
		return new SqlFragment(sql, params);
	}

	/** True for the schools (column {@code schoolExpr}) the user belongs to as student, staff or guardian, or manages the feed of. */
	SqlFragment schoolMember(CurrentUser user, String schoolExpr) {
		Map<String, Object> params = new HashMap<>();
		params.put("fv_user", user.userId());
		List<String> any = new ArrayList<>();
		any.add("exists (select 1 from school_students fv_ss where fv_ss.school_id = " + schoolExpr
				+ " and fv_ss.student_id = :fv_user and fv_ss.status = 'ACTIVE')");
		any.add("exists (select 1 from school_staff fv_st where fv_st.school_id = " + schoolExpr
				+ " and fv_st.user_id = :fv_user and fv_st.status = 'ACTIVE')");
		any.add("exists (select 1 from student_guardians fv_sg join school_students fv_gs on fv_gs.student_id = fv_sg.student_id"
				+ " and fv_gs.status = 'ACTIVE' where fv_sg.guardian_id = :fv_user and fv_sg.status = 'ACTIVE'"
				+ " and not fv_sg.custody_restricted and fv_sg.can_view_academics and fv_gs.school_id = " + schoolExpr + ")");
		AccessibleScopes managed = this.access.accessible(user, Permissions.SCHOOL_FEED_MANAGE);
		if (managed.platform()) {
			return SqlFragment.always();
		}
		if (!managed.schoolIds().isEmpty()) {
			any.add(schoolExpr + " in (:fv_schools)");
			params.put("fv_schools", managed.schoolIds());
		}
		if (!managed.organizationIds().isEmpty()) {
			any.add(schoolExpr + " in (select fv_o.id from schools fv_o where fv_o.organization_id in (:fv_orgs))");
			params.put("fv_orgs", managed.organizationIds());
		}
		if (!managed.countryCodes().isEmpty()) {
			any.add(schoolExpr + " in (select fv_k.id from schools fv_k where fv_k.country_code in (:fv_countries))");
			params.put("fv_countries", managed.countryCodes());
		}
		return new SqlFragment("(" + String.join(" or ", any) + ")", params);
	}

	/** Whether the user belongs to the school (student, staff, guardian) or manages its feed. */
	boolean canSeeSchool(CurrentUser user, UUID schoolId) {
		SqlFragment member = schoolMember(user, ":fv_school");
		return this.jdbc.sql("select exists (select 1 from schools fv_sc where fv_sc.id = :fv_school and (" + member.sql() + "))")
			.param("fv_school", schoolId)
			.params(member.params())
			.query(Boolean.class)
			.single();
	}

	boolean canSeePost(CurrentUser user, UUID postId) {
		SqlFragment visible = visiblePosts(user, "p");
		return this.jdbc.sql("select exists (select 1 from posts p where p.id = :fv_post and (" + visible.sql() + "))")
			.param("fv_post", postId)
			.params(visible.params())
			.query(Boolean.class)
			.single();
	}

	boolean canSeeStory(CurrentUser user, UUID storyId) {
		SqlFragment visible = visibleStories(user, "s");
		return this.jdbc.sql("select exists (select 1 from stories s where s.id = :fv_story and (" + visible.sql() + "))")
			.param("fv_story", storyId)
			.params(visible.params())
			.query(Boolean.class)
			.single();
	}

}
