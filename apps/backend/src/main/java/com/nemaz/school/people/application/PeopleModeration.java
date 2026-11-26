package com.nemaz.school.people.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.identity.UserAccounts;
import com.nemaz.school.moderation.ReportTarget;
import com.nemaz.school.moderation.SchoolSuspension;
import com.nemaz.school.moderation.TargetRef;
import com.nemaz.school.moderation.TargetTypes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people module's side of the moderation SPIs.
 * <ul>
 * <li><b>Reporting a person (USER, docs/Safeguarding_Rules.md S5.1):</b> allowed when the reporter can see them in a shared
 * context: both ACTIVE in the same class; the reporter ACTIVE staff of a school where the person is ACTIVE staff or an ACTIVE
 * student; a guardian (ACTIVE, not custody-restricted link) reporting the staff of their child's school. A student can report
 * only people of their own classes. Nothing else: there is no way to report somebody just by knowing their id. Conversations
 * are the messaging module's context.</li>
 * <li><b>School-level suspension (S2.4):</b> staff and students of the school become SUSPENDED (every "ACTIVE" check of the other
 * modules then excludes them); reversible, nothing is deleted. A suspended student is signed out of every session.</li>
 * </ul>
 */
@Component
class PeopleModeration implements ReportTarget, SchoolSuspension {

	private final JdbcClient jdbc;

	private final UserAccounts accounts;

	private final Clock clock;

	PeopleModeration(JdbcClient jdbc, UserAccounts accounts, Clock clock) {
		this.jdbc = jdbc;
		this.accounts = accounts;
		this.clock = clock;
	}

	@Override
	public Set<String> targetTypes() {
		return Set.of(TargetTypes.USER);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TargetRef> resolveFor(CurrentUser reporter, String type, UUID targetId) {
		UUID me = reporter.userId();
		if (me.equals(targetId)) {
			return Optional.empty();
		}
		boolean exists = this.jdbc.sql("select exists (select 1 from users where id = :t and deleted_at is null)")
			.param("t", targetId)
			.query(Boolean.class)
			.single();
		if (!exists) {
			return Optional.empty();
		}
		UUID school = sharedClassSchool(me, targetId);
		if (school == null) {
			school = staffSchool(me, targetId);
		}
		if (school == null) {
			school = guardianOfStaffSchool(me, targetId);
		}
		return Optional.ofNullable(school).map((s) -> new TargetRef(TargetTypes.USER, targetId, s, targetId));
	}

	@Override
	public Optional<TargetRef> describe(String type, UUID id) {
		return Optional.empty(); // a person is reported only through the endpoint, never through an event
	}

	/** Both ACTIVE members (student or teacher) of one class. */
	private UUID sharedClassSchool(UUID me, UUID other) {
		return this.jdbc.sql("""
				with mine as (
				  select class_id, school_id from class_students where student_id = :me and status = 'ACTIVE'
				  union select class_id, school_id from class_teachers where teacher_id = :me and status = 'ACTIVE'),
				theirs as (
				  select class_id from class_students where student_id = :other and status = 'ACTIVE'
				  union select class_id from class_teachers where teacher_id = :other and status = 'ACTIVE')
				select mine.school_id from mine join theirs on theirs.class_id = mine.class_id limit 1""")
			.param("me", me)
			.param("other", other)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	/** The reporter is ACTIVE staff of a school where the person is ACTIVE staff or an ACTIVE student. */
	private UUID staffSchool(UUID me, UUID other) {
		return this.jdbc.sql("""
				select st.school_id from school_staff st
				where st.user_id = :me and st.status = 'ACTIVE'
				  and (exists (select 1 from school_staff o where o.school_id = st.school_id and o.user_id = :other and o.status = 'ACTIVE')
				       or exists (select 1 from school_students s where s.school_id = st.school_id and s.student_id = :other and s.status = 'ACTIVE'))
				limit 1""")
			.param("me", me)
			.param("other", other)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	/** A guardian with an ACTIVE, unrestricted link to a child of the school reports that school's staff. */
	private UUID guardianOfStaffSchool(UUID me, UUID other) {
		return this.jdbc.sql("""
				select ss.school_id from student_guardians sg
				join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE'
				where sg.guardian_id = :me and sg.status = 'ACTIVE' and not sg.custody_restricted
				  and exists (select 1 from school_staff o where o.school_id = ss.school_id and o.user_id = :other and o.status = 'ACTIVE')
				limit 1""")
			.param("me", me)
			.param("other", other)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// SchoolSuspension

	@Override
	@Transactional
	public Result suspend(UUID userId, UUID schoolId) {
		Timestamp now = Timestamp.from(this.clock.instant());
		List<UUID> staff = this.jdbc.sql("""
				update school_staff set status = 'SUSPENDED', updated_at = :now, version = version + 1
				where school_id = :s and user_id = :u and status = 'ACTIVE' returning id""")
			.param("now", now)
			.param("s", schoolId)
			.param("u", userId)
			.query(UUID.class)
			.list();
		List<UUID> students = this.jdbc.sql("""
				update school_students set status = 'SUSPENDED', updated_at = :now, version = version + 1
				where school_id = :s and student_id = :u and status = 'ACTIVE' returning id""")
			.param("now", now)
			.param("s", schoolId)
			.param("u", userId)
			.query(UUID.class)
			.list();
		if (!students.isEmpty()) {
			this.accounts.signOutEverywhere(userId);
		}
		List<UUID> affected = new java.util.ArrayList<>(staff);
		affected.addAll(students);
		return affected.isEmpty() ? Result.nothing() : new Result(true, affected);
	}

	@Override
	@Transactional
	public void lift(UUID userId, UUID schoolId, List<UUID> affected) {
		if (affected.isEmpty()) {
			return;
		}
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				update school_staff set status = 'ACTIVE', updated_at = :now, version = version + 1
				where id in (:ids) and status = 'SUSPENDED'""")
			.param("now", now)
			.param("ids", affected)
			.update();
		this.jdbc.sql("""
				update school_students set status = 'ACTIVE', updated_at = :now, version = version + 1
				where id in (:ids) and status = 'SUSPENDED'""")
			.param("now", now)
			.param("ids", affected)
			.update();
	}

}
