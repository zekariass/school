package com.nemaz.school.attendance.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.attendance.AttendanceStatus;
import com.nemaz.school.attendance.application.CheckinModels.CheckinResultCode;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The append-only ledger of check-in attempts ({@code attendance_checkins}, a trigger refuses UPDATE and DELETE). Every
 * attempt that names a person is one row, accepted or not, in the transaction of the attempt (so a refused scan that rolls
 * back leaves nothing, and a refusal that is answered keeps its row). It holds ids, the result and times: never a token, a
 * code or a hash. An attempt on an unknown credential names nobody and is not a row (it is audited without the token).
 */
@Component
class CheckinLedger {

	private final JdbcClient jdbc;

	CheckinLedger(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * One attempt. {@code id} is made before the attempt is judged so that an accepted mark can point at it;
	 * {@code subjectType} is STUDENT or STAFF; {@code personId} is the student's user id or the school_staff id.
	 */
	record Attempt(UUID id, UUID schoolId, String subjectType, UUID personId, UUID classId, LocalDate date, String slot,
			UUID rollSessionId, String method, UUID scannerUserId, UUID credentialId, UUID qrSessionId, Instant checkedInAt,
			Instant clientTs, UUID clientOpId) {

		static Attempt of(UUID schoolId, String subjectType, UUID personId, UUID classId, LocalDate date, String slot, String method,
				UUID scannerUserId, UUID credentialId, UUID qrSessionId, Instant checkedInAt, Instant clientTs, UUID clientOpId) {
			return new Attempt(Ids.newId(), schoolId, subjectType, personId, classId, date, slot, null, method, scannerUserId,
					credentialId, qrSessionId, checkedInAt, clientTs, clientOpId);
		}

		Attempt withRoll(UUID rollSessionId) {
			return new Attempt(this.id, this.schoolId, this.subjectType, this.personId, this.classId, this.date, this.slot,
					rollSessionId, this.method, this.scannerUserId, this.credentialId, this.qrSessionId, this.checkedInAt,
					this.clientTs, this.clientOpId);
		}

		boolean student() {
			return "STUDENT".equals(this.subjectType);
		}

	}

	/** A stored attempt, as far as a retry of the same client operation needs it. */
	record Stored(UUID id, CheckinResultCode result, UUID personId, UUID scannerUserId, String subjectType, AttendanceStatus markedStatus,
			Integer minutesLate) {
	}

	/** Appends the attempt with its result; {@code marked*} only for ACCEPTED. Returns the attempt's id. */
	UUID append(Attempt a, CheckinResultCode result, AttendanceStatus markedStatus, Integer minutesLate) {
		if (result == CheckinResultCode.REJECTED_INVALID) {
			throw new IllegalArgumentException("An attempt on an unknown credential is not a ledger row");
		}
		this.jdbc.sql("""
				insert into attendance_checkins (id, school_id, subject_type, student_id, staff_id, class_id, attendance_date, slot,
				                                 attendance_session_id, method, scanner_user_id, credential_id, qr_session_id, result,
				                                 marked_status, minutes_late, checked_in_at, client_ts, client_op_id)
				values (:id, :school, :type, :student, :staff, :class, :date, :slot, :roll, :method, :scanner, :credential, :qr,
				        :result, :marked, :minutes, :at, :clientTs, :op)""")
			.param("id", a.id())
			.param("school", a.schoolId())
			.param("type", a.subjectType())
			.param("student", a.student() ? a.personId() : null)
			.param("staff", a.student() ? null : a.personId())
			.param("class", a.classId())
			.param("date", (a.date() != null) ? java.sql.Date.valueOf(a.date()) : null)
			.param("slot", a.slot())
			.param("roll", a.rollSessionId())
			.param("method", a.method())
			.param("scanner", a.scannerUserId())
			.param("credential", a.credentialId())
			.param("qr", a.qrSessionId())
			.param("result", result.name())
			.param("marked", (markedStatus != null) ? markedStatus.name() : null)
			.param("minutes", minutesLate)
			.param("at", Timestamp.from(a.checkedInAt()))
			.param("clientTs", (a.clientTs() != null) ? Timestamp.from(a.clientTs()) : null)
			.param("op", a.clientOpId())
			.update();
		return a.id();
	}

	/** The attempt stored for a client operation id of the school (a retry or a replay of the same scan). */
	Optional<Stored> byClientOp(UUID schoolId, UUID clientOpId) {
		if (clientOpId == null) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				select id, result, coalesce(student_id, staff_id) as person_id, scanner_user_id, subject_type, marked_status, minutes_late
				from attendance_checkins where school_id = :s and client_op_id = :op""")
			.param("s", schoolId)
			.param("op", clientOpId)
			.query((rs, i) -> new Stored(Rows.uuid(rs, "id"), CheckinResultCode.valueOf(rs.getString("result")),
					Rows.uuid(rs, "person_id"), Rows.uuid(rs, "scanner_user_id"), rs.getString("subject_type"),
					(rs.getString("marked_status") != null) ? AttendanceStatus.valueOf(rs.getString("marked_status")) : null,
					Rows.integer(rs, "minutes_late")))
			.optional();
	}

	/** Has the person an ACCEPTED check-in in the rotating session (removed ones count: the ledger keeps them)? */
	boolean acceptedIn(UUID qrSessionId, UUID personId) {
		return this.jdbc.sql("""
				select exists (select 1 from attendance_checkins where qr_session_id = :q and result = 'ACCEPTED'
				               and coalesce(student_id, staff_id) = :p)""")
			.param("q", qrSessionId)
			.param("p", personId)
			.query(Boolean.class)
			.single();
	}

	/** Did a scanner other than {@code scannerUserId} read the credential since {@code since} (any result)? */
	boolean readByAnotherScannerSince(UUID credentialId, UUID scannerUserId, Instant since) {
		return this.jdbc.sql("""
				select exists (select 1 from attendance_checkins where credential_id = :c and scanner_user_id <> :u
				               and checked_in_at >= :since)""")
			.param("c", credentialId)
			.param("u", scannerUserId)
			.param("since", Timestamp.from(since))
			.query(Boolean.class)
			.single();
	}

}
