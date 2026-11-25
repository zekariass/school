package com.nemaz.school.people;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the roster import (imports module) needs from the people module: matching and writing students and staff
 * on behalf of an administrator who has already been authorized for the import ({@code school.import.manage}).
 * No method checks permissions. Writes join the caller's transaction, publish the usual domain events
 * ({@link StudentEnrolledInSchool}, {@link StaffAdded}) and write the usual audit rows; they never grant
 * administrative roles and never create guardians. Everything is scoped to the given school.
 */
public interface PeopleImports {

	// --- students ---------------------------------------------------------------------------

	/** The student with this number in the school's enrollments (any status), empty when none. */
	Optional<StudentSnapshot> studentByNumber(UUID schoolId, String studentNumber);

	/** The student's latest enrollment in the school, empty when the student is not (or never was) enrolled there. */
	Optional<StudentSnapshot> student(UUID schoolId, UUID studentId);

	/**
	 * ACTIVE students of the school whose name key is at least {@code minSimilarity} (0..1, trigram similarity) like
	 * {@code nameFold}, born within a year of {@code dateOfBirth}, most similar first.
	 */
	List<StudentCandidate> studentCandidates(UUID schoolId, String nameFold, LocalDate dateOfBirth, double minSimilarity,
			int limit);

	/** How many more students the school's plan allows (empty: unlimited). */
	java.util.OptionalLong remainingStudentCapacity(UUID schoolId);

	/** Creates a MANAGED student account with profile and enrollment (and class membership when {@code classId} is set). */
	UUID createStudent(UUID schoolId, UUID actorId, NewStudent student);

	/** Changes the given fields (null = unchanged); {@code externalRef} is the id from the file, kept on the enrollment. */
	void updateStudent(UUID schoolId, UUID actorId, UUID studentId, StudentChange change, String externalRef);

	// --- staff ------------------------------------------------------------------------------

	/**
	 * Live (INVITED or ACTIVE) staff of the school whose account has this phone or email; several results mean the
	 * two contacts belong to different people.
	 */
	List<StaffSnapshot> staffByContact(UUID schoolId, String phoneE164, String email);

	/** A live (INVITED or ACTIVE) staff member of the school by id. */
	Optional<StaffSnapshot> staff(UUID schoolId, UUID staffId);

	/** Adds a person (account found by phone or email, or created) to the staff; never an administrative role. */
	NewStaffResult addStaff(UUID schoolId, UUID actorId, NewStaff staff);

	/** Changes job title and campus of a staff member (null = unchanged). */
	void updateStaff(UUID schoolId, UUID actorId, UUID staffId, String jobTitle, UUID campusId, String externalRef);

	/** A student's enrollment with the personal data the import compares. */
	record StudentSnapshot(UUID studentId, UUID enrollmentId, String status, String displayName, String givenName,
			String middleName, String familyName, LocalDate dateOfBirth, String gender, String studentNumber, UUID campusId,
			String username) {

		public boolean live() {
			return "ACTIVE".equals(this.status) || "PENDING".equals(this.status);
		}

	}

	record StudentCandidate(UUID studentId, String displayName, LocalDate dateOfBirth, String studentNumber,
			double similarity) {
	}

	record NewStudent(String givenName, String middleName, String familyName, LocalDate dateOfBirth, String gender,
			String studentNumber, UUID campusId, String username, UUID classId, String externalRef) {
	}

	record StudentChange(String givenName, String middleName, String familyName, LocalDate dateOfBirth, String gender,
			String studentNumber, UUID campusId, String username) {
	}

	record StaffSnapshot(UUID staffId, UUID userId, String staffType, String jobTitle, UUID campusId, String externalRef,
			String displayName) {
	}

	record NewStaff(String phone, String email, String givenName, String familyName, String displayName,
			String staffType, String jobTitle, UUID campusId, String externalRef) {
	}

	record NewStaffResult(UUID staffId, UUID userId, boolean accountCreated) {
	}

}
