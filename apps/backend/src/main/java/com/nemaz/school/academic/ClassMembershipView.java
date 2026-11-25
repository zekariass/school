package com.nemaz.school.academic;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** A user's own ACTIVE membership of a class. {@code teacherRole} is set for teachers only. */
public record ClassMembershipView(@NotNull UUID classId, @NotNull Kind kind, TeacherRole teacherRole, UUID subjectId,
		@NotNull Instant since) {

	public enum Kind {

		STUDENT, TEACHER

	}

	public enum TeacherRole {

		LEAD, CO_TEACHER, SUBJECT_TEACHER, ASSISTANT

	}

}
