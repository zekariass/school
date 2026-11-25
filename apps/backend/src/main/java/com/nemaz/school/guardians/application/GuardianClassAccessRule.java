package com.nemaz.school.guardians.application;

import java.util.Map;
import java.util.Optional;

import com.nemaz.school.academic.ClassAccessRule;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.security.CurrentUser;

import org.springframework.stereotype.Component;

/**
 * The guardian branch of "may user U see class C" (Database_Design §5, spec §11, §102): U holds an ACTIVE link without
 * a custody restriction and with {@code can_view_academics} to a student who is ACTIVE in C's roster and an ACTIVE
 * student of C's school. Contributes to view access only: the roster ({@code ClassAccess.canViewRoster}), teaching and
 * managing never consider rules, so guardians see their child's classes but not the other children.
 */
@Component
class GuardianClassAccessRule implements ClassAccessRule {

	@Override
	public Optional<SqlFragment> visibleClasses(CurrentUser user, String c) {
		if (user == null) {
			return Optional.empty();
		}
		return Optional.of(new SqlFragment("""
				exists (select 1 from student_guardians gr_sg
				        join class_students gr_cs on gr_cs.student_id = gr_sg.student_id and gr_cs.status = 'ACTIVE'
				        join school_students gr_ss on gr_ss.student_id = gr_sg.student_id and gr_ss.status = 'ACTIVE'
				        where gr_sg.guardian_id = :gr_user and gr_sg.status = 'ACTIVE' and not gr_sg.custody_restricted
				          and gr_sg.can_view_academics and gr_cs.class_id = %1$s.id and gr_ss.school_id = %1$s.school_id)"""
			.formatted(c), Map.of("gr_user", user.userId())));
	}

}
