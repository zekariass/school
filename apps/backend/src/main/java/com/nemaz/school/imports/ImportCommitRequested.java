package com.nemaz.school.imports;

import java.util.UUID;

/** A commit was requested for the job (published in the requesting transaction; delivered at least once). */
public record ImportCommitRequested(UUID jobId) {
}
