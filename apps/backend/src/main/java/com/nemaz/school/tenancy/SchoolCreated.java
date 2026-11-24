package com.nemaz.school.tenancy;

import java.util.UUID;

public record SchoolCreated(UUID schoolId, String countryCode, UUID organizationId, String slug) {
}
