package com.nemaz.school.people;

import java.util.UUID;

/** A staff member left the school (status FORMER); their class teaching ended with it. */
public record StaffDeactivated(UUID schoolId, UUID staffId, UUID userId, String staffType, UUID actorId) {
}
