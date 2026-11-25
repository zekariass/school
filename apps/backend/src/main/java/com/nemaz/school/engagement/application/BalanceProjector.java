package com.nemaz.school.engagement.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointTransactionRecorded;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code point_balances} projection. Every ledger row announces itself ({@code PointTransactionRecorded}); the
 * listener recomputes the one balance row the transaction belongs to (student, academic year, context, class) from the
 * ledger instead of adding a delta, which makes delivery idempotent (at-least-once, in any order) and lets the same
 * routine repair drift. An advisory lock per balance row serializes concurrent recomputations, so parallel awards
 * never lose an update. HOME rows have their own balance rows (context HOME) and are never part of a class or school
 * aggregate (D2).
 */
@Component
class BalanceProjector {

	static final String NO_CLASS = "00000000-0000-0000-0000-000000000000";

	private final JdbcClient jdbc;

	BalanceProjector(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(PointTransactionRecorded event) {
		recompute(event.schoolId(), event.studentId(), event.academicYearId(), event.context(), event.classId());
	}

	/** Rewrites one balance row from the ledger. */
	@Transactional
	public void recompute(UUID schoolId, UUID studentId, UUID yearId, PointContext context, UUID classId) {
		UUID classKey = (classId != null) ? classId : UUID.fromString(NO_CLASS);
		Locks.advisory(this.jdbc, "points-balance:" + studentId + ":" + yearId + ":" + context + ":" + classKey);
		this.jdbc.sql("""
				insert into point_balances (student_id, school_id, academic_year_id, context, class_id, positive_total,
				                            needs_work_total, last_transaction_at)
				select :student, :school, :year, :context, :class,
				       coalesce(sum(points) filter (where polarity = 'POSITIVE'), 0),
				       coalesce(sum(points) filter (where polarity = 'NEEDS_WORK'), 0),
				       max(created_at)
				from point_transactions
				where student_id = :student and academic_year_id = :year and context = :context
				  and coalesce(class_id, cast(:class as uuid)) = :class
				on conflict (student_id, academic_year_id, context, class_id) do update
				  set positive_total = excluded.positive_total, needs_work_total = excluded.needs_work_total,
				      last_transaction_at = excluded.last_transaction_at""")
			.param("student", studentId)
			.param("school", schoolId)
			.param("year", yearId)
			.param("context", context.name())
			.param("class", classKey)
			.update();
	}

	/**
	 * Balance keys whose stored totals differ from the ledger (including keys with no balance row yet and balance rows
	 * without ledger rows), at most {@code limit}.
	 */
	@Transactional(readOnly = true)
	List<Drift> findDrift(int limit) {
		return this.jdbc.sql("""
				with keyed as (
				  select student_id, school_id, academic_year_id, context, coalesce(class_id, cast(:none as uuid)) as class_key,
				         points, polarity
				  from point_transactions
				), ledger as (
				  select student_id, school_id, academic_year_id, context, class_key,
				         coalesce(sum(points) filter (where polarity = 'POSITIVE'), 0) as pos,
				         coalesce(sum(points) filter (where polarity = 'NEEDS_WORK'), 0) as nw
				  from keyed group by student_id, school_id, academic_year_id, context, class_key
				)
				select coalesce(l.student_id, b.student_id) as student_id, coalesce(l.school_id, b.school_id) as school_id,
				       coalesce(l.academic_year_id, b.academic_year_id) as academic_year_id,
				       coalesce(l.context, b.context) as context, coalesce(l.class_key, b.class_id) as class_key
				from ledger l
				full join point_balances b on b.student_id = l.student_id and b.academic_year_id = l.academic_year_id
				                          and b.context = l.context and b.class_id = l.class_key
				where (l.student_id is null and (b.positive_total <> 0 or b.needs_work_total <> 0))
				   or b.student_id is null
				   or l.pos <> b.positive_total or l.nw <> b.needs_work_total
				limit :limit""")
			.param("none", NO_CLASS)
			.param("limit", limit)
			.query((rs, i) -> new Drift(rs.getObject("student_id", UUID.class), rs.getObject("school_id", UUID.class),
					rs.getObject("academic_year_id", UUID.class), PointContext.valueOf(rs.getString("context")),
					rs.getObject("class_key", UUID.class)))
			.list();
	}

	/** A balance key (class null for the contexts without a class) that disagrees with the ledger. */
	record Drift(UUID studentId, UUID schoolId, UUID yearId, PointContext context, UUID classKey) {

		UUID classId() {
			return NO_CLASS.equals(this.classKey.toString()) ? null : this.classKey;
		}

	}

}
