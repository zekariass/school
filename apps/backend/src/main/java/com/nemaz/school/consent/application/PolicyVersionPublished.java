package com.nemaz.school.consent.application;

import java.util.UUID;

import com.nemaz.school.consent.ConsentType;

/** A school's policy version was published (internal to the consent module: asks guardians again when required). */
public record PolicyVersionPublished(UUID versionId, UUID documentId, UUID schoolId, ConsentType policyType,
		boolean requiresReconsent) {
}
