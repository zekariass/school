package com.nemaz.school.imports.application;

import java.time.Duration;

import com.nemaz.school.imports.domain.ImportLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Roster import settings ({@code school.imports.*}); every value has the default of the research note
 * (docs/research/M4_Roster_Import.md).
 *
 * @param maxFileBytes largest accepted file (the media upload caps it too)
 * @param maxRows data rows per file, header excluded
 * @param maxColumns columns per file
 * @param maxCellChars characters per cell
 * @param parseTimeout how long reading one file may take
 * @param previewRows rows returned by the synchronous preview
 * @param runningPerSchool dry runs and commits of one school that may run at the same time
 * @param createsPerHour jobs one user may create per hour
 * @param batchSize rows per transaction while checking and committing
 * @param autoMatchSimilarity name similarity (0..1) from which a student with the same birth date and a shared class
 * or guardian phone is matched without asking
 * @param possibleDuplicateSimilarity name similarity from which a student with a close birth date is offered as a
 * possible duplicate
 * @param retentionAfterCreate raw rows are purged this long after the job was created
 * @param retentionAfterCommit raw rows are purged this long after the job completed (the earlier of the two applies)
 * @param leaseDuration how long a worker owns a job before another may take it over
 * @param stuckAfter a validating or committing job without a live lease older than this is queued again
 */
@ConfigurationProperties("school.imports")
public record ImportProperties(@DefaultValue("10485760") long maxFileBytes, @DefaultValue("20000") int maxRows,
		@DefaultValue("60") int maxColumns, @DefaultValue("1000") int maxCellChars,
		@DefaultValue("PT30S") Duration parseTimeout, @DefaultValue("20") int previewRows,
		@DefaultValue("2") int runningPerSchool, @DefaultValue("20") int createsPerHour,
		@DefaultValue("100") int batchSize, @DefaultValue("0.9") double autoMatchSimilarity,
		@DefaultValue("0.6") double possibleDuplicateSimilarity, @DefaultValue("P30D") Duration retentionAfterCreate,
		@DefaultValue("P7D") Duration retentionAfterCommit, @DefaultValue("PT2M") Duration leaseDuration,
		@DefaultValue("PT3M") Duration stuckAfter) {

	public ImportLimits limits() {
		return new ImportLimits(this.maxFileBytes, this.maxRows, this.maxColumns, this.maxCellChars,
				ImportLimits.DEFAULTS.maxZipEntries(), ImportLimits.DEFAULTS.maxZipEntryBytes(),
				ImportLimits.DEFAULTS.maxZipTotalBytes(), ImportLimits.DEFAULTS.maxZipTextChars(), this.parseTimeout);
	}

}
