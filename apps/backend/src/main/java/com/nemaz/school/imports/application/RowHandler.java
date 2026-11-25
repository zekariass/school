package com.nemaz.school.imports.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.ImportModel.RowStatus;
import com.nemaz.school.imports.domain.DateParser;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.imports.domain.Problem;

/**
 * Checks and applies the rows of one import type. A handler is a bean; {@link #open} prepares a {@link Session} for
 * one run of one job (it loads the school's grade levels, classes, campuses ... once and remembers what the file
 * has already said, to find duplicates inside it).
 */
interface RowHandler {

	ImportType type();

	Session open(JobContext context);

	/** What a run needs to know about the job. {@code today} is the school's today (age checks). */
	record JobContext(UUID schoolId, UUID jobId, UUID actorId, UUID externalSystemId, DateParser.Options dateOptions,
			LocalDate today) {
	}

	/** A data row as the file gave it: the mapped fields (cleaned, null when empty), and the reader's notes. */
	record RowInput(int rowNumber, Map<String, String> values, Set<String> nativeDateFields, List<Problem> sourceWarnings) {

		String get(String field) {
			return this.values.get(field);
		}

	}

	/** The outcome of checking one row; {@code normalized} is what the commit will apply. */
	record Planned(RowStatus status, Action action, Map<String, Object> normalized, List<Problem> errors,
			List<Problem> warnings, List<Map<String, Object>> candidates, String entityType, UUID entityId) {
	}

	/** {@code result}: CREATED, UPDATED, UNCHANGED or SKIPPED. {@code externalId} gets a mapping when not null. */
	record Applied(String result, String entityType, UUID entityId, String externalId) {
	}

	interface Session {

		/** Rows come in file order. */
		Planned plan(RowInput row);

		/** Applies one stored row inside the caller's transaction; an exception rolls the batch back. */
		Applied apply(Row row);

	}

	/** Collects the problems of one row and builds its {@link Planned}. */
	final class Draft {

		final Map<String, Object> normalized = new LinkedHashMap<>();

		final List<Problem> errors = new ArrayList<>();

		final List<Problem> warnings = new ArrayList<>();

		final List<Map<String, Object>> candidates = new ArrayList<>();

		String entityType;

		UUID entityId;

		Draft(List<Problem> sourceWarnings) {
			this.warnings.addAll(sourceWarnings);
		}

		void error(String column, String code, String... args) {
			this.errors.add(Problem.of(column, code, args));
		}

		void warn(String column, String code, String... args) {
			this.warnings.add(Problem.of(column, code, args));
		}

		boolean ok() {
			return this.errors.isEmpty();
		}

		void put(String key, Object value) {
			if (value != null) {
				this.normalized.put(key, value);
			}
		}

		Planned invalid() {
			return new Planned(RowStatus.INVALID, null, this.normalized, this.errors, this.warnings, List.of(), null, null);
		}

		Planned valid(Action action) {
			return new Planned(RowStatus.VALID, action, this.normalized, this.errors, this.warnings, List.of(),
					this.entityType, this.entityId);
		}

		Planned duplicate() {
			return new Planned(RowStatus.DUPLICATE, null, this.normalized, this.errors, this.warnings, this.candidates,
					this.entityType, this.entityId);
		}

	}

}
