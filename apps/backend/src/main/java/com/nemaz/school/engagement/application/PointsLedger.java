package com.nemaz.school.engagement.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.engagement.PointTransactionRecorded;
import com.nemaz.school.engagement.PointTransactionType;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The append-only {@code point_transactions} table: rows are inserted (and announced with
 * {@link PointTransactionRecorded}) and read, never updated or deleted. Joins the caller's transaction.
 */
@Component
class PointsLedger {

	static final String COLUMNS = """
			id, school_id, student_id, context, class_id, academic_year_id, category_id, polarity, points, transaction_type,
			reverses_transaction_id, reason, awarded_by, awarder_role, award_batch_id, client_op_id, created_at""";

	private final JdbcClient jdbc;

	private final ApplicationEventPublisher events;

	PointsLedger(JdbcClient jdbc, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	/**
	 * Appends the row unless it collides with a unique rule (a reversal that already exists, a repeated client
	 * operation); returns whether it was inserted. The event is published only for an inserted row.
	 */
	boolean append(Row row) {
		int inserted = this.jdbc.sql("""
				insert into point_transactions (id, school_id, student_id, context, class_id, academic_year_id, category_id, polarity,
				                                points, transaction_type, reverses_transaction_id, reason, awarded_by, awarder_role,
				                                award_batch_id, client_op_id, created_at)
				values (:id, :school, :student, :context, :class, :year, :category, :polarity, :points, :type, :reverses, :reason,
				        :by, :role, :batch, :op, :at)
				on conflict do nothing""")
			.param("id", row.id())
			.param("school", row.schoolId())
			.param("student", row.studentId())
			.param("context", row.context().name())
			.param("class", row.classId())
			.param("year", row.academicYearId())
			.param("category", row.categoryId())
			.param("polarity", row.polarity().name())
			.param("points", row.points())
			.param("type", row.type().name())
			.param("reverses", row.reversesTransactionId())
			.param("reason", row.reason())
			.param("by", row.awardedBy())
			.param("role", row.awarderRole().name())
			.param("batch", row.awardBatchId())
			.param("op", row.clientOpId())
			.param("at", Timestamp.from(row.createdAt()))
			.update();
		if (inserted == 0) {
			return false;
		}
		this.events.publishEvent(new PointTransactionRecorded(row.id(), row.schoolId(), row.studentId(), row.context(),
				row.classId(), row.academicYearId(), row.categoryId(), row.polarity(), row.type(), row.points(), row.awardedBy()));
		return true;
	}

	Optional<Row> find(UUID schoolId, UUID id) {
		return this.jdbc.sql("select " + COLUMNS + " from point_transactions where id = :id and school_id = :s")
			.param("id", id)
			.param("s", schoolId)
			.query(PointsLedger::row)
			.optional();
	}

	/** The rows an earlier call with this operation id wrote (any number: one per student). */
	List<Row> byOperation(UUID awardedBy, UUID clientOpId, PointTransactionType type) {
		return this.jdbc.sql("select " + COLUMNS + """
				 from point_transactions
				where awarded_by is not distinct from :by and client_op_id = :op and transaction_type = :type
				order by id""")
			.param("by", awardedBy)
			.param("op", clientOpId)
			.param("type", type.name())
			.query(PointsLedger::row)
			.list();
	}

	boolean isReversed(UUID transactionId) {
		return this.jdbc.sql("select exists (select 1 from point_transactions where reverses_transaction_id = :id)")
			.param("id", transactionId)
			.query(Boolean.class)
			.single();
	}

	/** The AWARD rows of a batch (a whole-class or multi-select award), oldest first. */
	List<Row> awardsOfBatch(UUID schoolId, UUID batchId) {
		return this.jdbc.sql("select " + COLUMNS + """
				 from point_transactions
				where school_id = :s and award_batch_id = :b and transaction_type = 'AWARD' order by id""")
			.param("s", schoolId)
			.param("b", batchId)
			.query(PointsLedger::row)
			.list();
	}

	static Row row(ResultSet rs, int i) throws SQLException {
		return new Row(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "student_id"),
				PointContext.valueOf(rs.getString("context")), Rows.uuid(rs, "class_id"), Rows.uuid(rs, "academic_year_id"),
				Rows.uuid(rs, "category_id"), PointPolarity.valueOf(rs.getString("polarity")), rs.getInt("points"),
				PointTransactionType.valueOf(rs.getString("transaction_type")), Rows.uuid(rs, "reverses_transaction_id"),
				rs.getString("reason"), Rows.uuid(rs, "awarded_by"), AwarderRole.valueOf(rs.getString("awarder_role")),
				Rows.uuid(rs, "award_batch_id"), Rows.uuid(rs, "client_op_id"), Rows.instant(rs, "created_at"));
	}

	enum AwarderRole {

		TEACHER, GUARDIAN, SCHOOL_ADMIN, SYSTEM

	}

	/** A ledger row. {@code reason} is the note (child data: never logged, audited or put in events). */
	record Row(UUID id, UUID schoolId, UUID studentId, PointContext context, UUID classId, UUID academicYearId,
			UUID categoryId, PointPolarity polarity, int points, PointTransactionType type, UUID reversesTransactionId,
			String reason, UUID awardedBy, AwarderRole awarderRole, UUID awardBatchId, UUID clientOpId, Instant createdAt) {

		@Override
		public String toString() {
			return "Row[" + this.id + "]";
		}

	}

}
