package com.nemaz.school.imports.application;

import com.nemaz.school.imports.ImportCommitRequested;
import com.nemaz.school.imports.ImportDryRunRequested;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Starts the background work of an import. Delivery is at least once, so both listeners are idempotent: the work
 * checks the job's state and takes its lease first. Errors never propagate (the job ends in FAILED, and the user
 * decides whether to run it again), so an event is never redelivered because of a bad file.
 */
@Component
class ImportEvents {

	private final ImportDryRun dryRun;

	private final ImportCommit commit;

	ImportEvents(ImportDryRun dryRun, ImportCommit commit) {
		this.dryRun = dryRun;
		this.commit = commit;
	}

	@ApplicationModuleListener
	void on(ImportDryRunRequested event) {
		this.dryRun.run(event.jobId());
	}

	@ApplicationModuleListener
	void on(ImportCommitRequested event) {
		this.commit.run(event.jobId());
	}

}
