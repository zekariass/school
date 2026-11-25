package com.nemaz.school.people;

import java.util.UUID;

/** A student login code was issued. The code itself is never part of an event. */
public record LoginCodeIssued(UUID codeId, UUID schoolId, UUID classId, UUID studentId, String type, java.time.Instant expiresAt, UUID issuedBy) {
}
