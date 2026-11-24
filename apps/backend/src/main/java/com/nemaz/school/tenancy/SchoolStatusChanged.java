package com.nemaz.school.tenancy;

import java.util.UUID;

public record SchoolStatusChanged(UUID schoolId, String previousStatus, String newStatus) {
}
