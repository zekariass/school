package com.nemaz.school.people;

import java.util.UUID;

/** A student was enrolled in a school ({@code studentId} is the student's user id). {@code classId} is set when created inline into a class. */
public record StudentEnrolledInSchool(UUID schoolId, UUID studentId, UUID enrollmentId, UUID classId, UUID createdBy) {
}
