package com.nemaz.school.academic;

import java.util.UUID;

/** A class was created (published in the creating transaction; delivered at least once). */
public record ClassCreated(UUID classId, UUID schoolId, UUID campusId, UUID academicYearId, UUID createdBy) {
}
