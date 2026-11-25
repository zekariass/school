package com.nemaz.school.people;

import java.util.UUID;

/** A student login code was revoked (reason: REPLACED, REVOKED, WITHDRAWN). */
public record LoginCodeRevoked(UUID codeId, UUID schoolId, UUID studentId, String reason, UUID actorId) {
}
