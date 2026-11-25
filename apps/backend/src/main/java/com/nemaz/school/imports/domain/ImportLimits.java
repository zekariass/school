package com.nemaz.school.imports.domain;

import java.time.Duration;

/** What a file may contain. The values are configuration ({@code school.imports.*}); the defaults are the research note's. */
public record ImportLimits(long maxFileBytes, int maxRows, int maxColumns, int maxCellChars, int maxZipEntries,
		long maxZipEntryBytes, long maxZipTotalBytes, long maxZipTextChars, Duration parseTimeout) {

	public static final ImportLimits DEFAULTS = new ImportLimits(10L * 1024 * 1024, 20_000, 60, 1_000, 1_000,
			100L * 1024 * 1024, 250L * 1024 * 1024, 10_000_000L, Duration.ofSeconds(30));

}
