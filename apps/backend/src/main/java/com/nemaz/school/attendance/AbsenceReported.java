package com.nemaz.school.attendance;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A guardian filed an absence report (published in the transaction that stored it). Notifies the student's teachers.
 * Carries ids and dates only: the reason and the note are health related and never leave the database through events.
 */
public record AbsenceReported(UUID reportId, UUID schoolId, UUID studentId, LocalDate fromDate, LocalDate toDate,
		UUID reportedBy) {
}
