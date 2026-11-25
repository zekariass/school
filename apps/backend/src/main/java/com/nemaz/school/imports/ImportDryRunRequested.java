package com.nemaz.school.imports;

import java.util.UUID;

/** A dry run was requested for the job (published in the requesting transaction; delivered at least once). */
public record ImportDryRunRequested(UUID jobId) {
}
