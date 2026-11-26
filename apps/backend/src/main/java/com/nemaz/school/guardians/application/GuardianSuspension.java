package com.nemaz.school.guardians.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.moderation.SchoolSuspension;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guardian links of a school-level suspension (docs/Safeguarding_Rules.md S2.4): the person's ACTIVE links to children of the
 * school become custody-restricted, which every module already treats as "a stranger to the school and the child" (no feed, no
 * messaging, no consent, no notifications of the child). A lift un-restricts exactly the links the suspension restricted, never
 * a restriction that was there before. Nothing is revoked or deleted.
 */
@Component
class GuardianSuspension implements SchoolSuspension {

	private final JdbcClient jdbc;

	private final Clock clock;

	GuardianSuspension(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional
	public Result suspend(UUID userId, UUID schoolId) {
		List<UUID> links = this.jdbc.sql("""
				update student_guardians sg set custody_restricted = true, updated_at = :now, version = version + 1
				where sg.guardian_id = :u and sg.status = 'ACTIVE' and not sg.custody_restricted
				  and sg.student_id in (select student_id from school_students where school_id = :s
				                        and status in ('PENDING', 'ACTIVE', 'SUSPENDED'))
				returning sg.id""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("u", userId)
			.param("s", schoolId)
			.query(UUID.class)
			.list();
		return links.isEmpty() ? Result.nothing() : new Result(true, links);
	}

	@Override
	@Transactional
	public void lift(UUID userId, UUID schoolId, List<UUID> affected) {
		if (affected.isEmpty()) {
			return;
		}
		this.jdbc.sql("update student_guardians set custody_restricted = false, updated_at = :now, version = version + 1 where id in (:ids) and custody_restricted")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("ids", affected)
			.update();
	}

}
