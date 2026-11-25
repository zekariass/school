package com.nemaz.school.people;

import java.util.UUID;

/** A person was added to a school's staff. {@code createdUser} is true when an account was created for them. */
public record StaffAdded(UUID schoolId, UUID staffId, UUID userId, String staffType, boolean createdUser, UUID actorId) {
}
