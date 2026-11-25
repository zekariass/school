package com.nemaz.school.people;

import java.util.UUID;

/** A student's enrollment ended (status WITHDRAWN); class memberships and login codes ended with it. */
public record StudentWithdrawnFromSchool(UUID schoolId, UUID studentId, UUID enrollmentId, UUID actorId) {
}
