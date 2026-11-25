package com.nemaz.school.imports.api;

import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.imports.application.ImportModel.RowStatus;
import com.nemaz.school.imports.application.ImportModel.Status;
import com.nemaz.school.imports.application.ImportOptions;
import com.nemaz.school.imports.application.ImportReports;
import com.nemaz.school.imports.application.ImportService;
import com.nemaz.school.imports.application.ImportService.CreateImport;
import com.nemaz.school.imports.application.ImportService.Resolve;
import com.nemaz.school.imports.application.ImportService.SaveMapping;
import com.nemaz.school.imports.application.ImportViews.ImportJobView;
import com.nemaz.school.imports.application.ImportViews.ImportPreview;
import com.nemaz.school.imports.application.ImportViews.ImportRowView;
import com.nemaz.school.imports.application.ImportViews.ImportTypeView;
import com.nemaz.school.imports.application.ImportViews.Resolution;
import com.nemaz.school.imports.domain.ImportType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roster imports of a school (see {@link ImportService} for the state machine). The wizard: create the job from an
 * uploaded {@code IMPORT_FILE} (preview and suggested mapping come back), save the mapping (and options), dry-run,
 * poll the job until VALIDATED, review rows, resolve possible duplicates, commit, poll until COMPLETED.
 */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/imports")
@Tag(name = "imports")
public class ImportController {

	private final ImportService imports;

	private final ImportReports reports;

	ImportController(ImportService imports, ImportReports reports) {
		this.imports = imports;
		this.reports = reports;
	}

	@GetMapping("/types")
	@Operation(operationId = "listImportTypes",
			summary = "The kinds of file that can be imported, with their columns (labels in the reader's language) and required fields")
	public java.util.List<ImportTypeView> types(@PathVariable UUID schoolId) {
		return this.imports.types(CurrentUser.require(), schoolId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createImport",
			summary = "Start an import from an uploaded file (media purpose IMPORT_FILE, READY): reads the header and the first rows, detects encoding and delimiter, suggests the column mapping")
	public ImportPreview create(@PathVariable UUID schoolId, @Valid @RequestBody CreateImportRequest r) {
		return this.imports.create(CurrentUser.require(), schoolId,
				new CreateImport(r.importType(), r.mediaId(), r.options(), r.mappingProfileId()));
	}

	@GetMapping
	@Operation(operationId = "listImports", summary = "Import history, newest first; filters status and importType")
	public CursorPage<ImportJobView> list(@PathVariable UUID schoolId, @RequestParam(required = false) Status status,
			@RequestParam(required = false) ImportType importType, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.imports.list(CurrentUser.require(), schoolId, status, importType, cursor, limit);
	}

	@GetMapping("/{importId}")
	@Operation(operationId = "getImport",
			summary = "One import: status, progress (poll while VALIDATING or COMMITTING), row counts, commit result, failure")
	public ImportJobView get(@PathVariable UUID schoolId, @PathVariable UUID importId) {
		return this.imports.get(CurrentUser.require(), schoolId, importId);
	}

	@GetMapping("/{importId}/preview")
	@Operation(operationId = "previewImport",
			summary = "The preview again (headers, first rows, mapping so far), for reloading the mapping step. When any of encoding, delimiter or sheet is given (an empty value means detect), the file is read that way instead and the mapping is suggested from the new headers; nothing is saved until the mapping is")
	public ImportPreview preview(@PathVariable UUID schoolId, @PathVariable UUID importId,
			@RequestParam(required = false) String encoding, @RequestParam(required = false) String delimiter,
			@RequestParam(required = false) String sheet) {
		return this.imports.preview(CurrentUser.require(), schoolId, importId, encoding, delimiter, sheet);
	}

	@PutMapping("/{importId}/mapping")
	@Operation(operationId = "saveImportMapping",
			summary = "Save the column mapping (field key to column index) and reading options; the file is read again with them and the job is PREVIEWED. 400 import.mapping_incomplete lists what is missing. Optionally saves the mapping as a profile")
	public ImportPreview saveMapping(@PathVariable UUID schoolId, @PathVariable UUID importId,
			@Valid @RequestBody SaveMappingRequest r) {
		return this.imports.saveMapping(CurrentUser.require(), schoolId, importId,
				new SaveMapping(r.columnMapping(), r.options(), r.saveAsProfile()));
	}

	@PostMapping("/{importId}/dry-run")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(operationId = "startImportDryRun",
			summary = "Check every row in the background (nothing is written to the school); poll the import until VALIDATED or FAILED. Allowed again after a change of mapping or a failure")
	public ImportJobView dryRun(@PathVariable UUID schoolId, @PathVariable UUID importId) {
		return this.imports.startDryRun(CurrentUser.require(), schoolId, importId);
	}

	@GetMapping("/{importId}/rows")
	@Operation(operationId = "listImportRows",
			summary = "The checked rows in file order (keyset by row number); filter status VALID, INVALID, DUPLICATE or SKIPPED. Errors and warnings are in the reader's language")
	public CursorPage<ImportRowView> rows(@PathVariable UUID schoolId, @PathVariable UUID importId,
			@RequestParam(required = false) RowStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.imports.rows(CurrentUser.require(), schoolId, importId, status, cursor, limit);
	}

	@PostMapping("/{importId}/rows/{rowId}/resolve")
	@Operation(operationId = "resolveImportRow",
			summary = "Decide a possible duplicate: LINK to an existing student (studentId), CREATE_NEW or SKIP. SKIP is also allowed for any row that was not applied yet")
	public ImportRowView resolve(@PathVariable UUID schoolId, @PathVariable UUID importId, @PathVariable UUID rowId,
			@Valid @RequestBody ResolveRequest r) {
		return this.imports.resolve(CurrentUser.require(), schoolId, importId, rowId, new Resolve(r.resolution(), r.studentId()));
	}

	@GetMapping(value = "/{importId}/error-report", produces = "text/csv")
	@Operation(operationId = "downloadImportErrorReport",
			summary = "CSV of every problem: the file's columns plus row_number, severity, field, message (UTF-8 with BOM, CRLF, formula-safe cells). Not cached")
	public ResponseEntity<byte[]> errorReport(@PathVariable UUID schoolId, @PathVariable UUID importId) {
		ImportReports.Report report = this.reports.errorReport(CurrentUser.require(), schoolId, importId);
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(report.filename()).build().toString())
			.cacheControl(CacheControl.noStore())
			.contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
			.body(report.content());
	}

	@PostMapping("/{importId}/commit")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Operation(operationId = "commitImport",
			summary = "Apply a VALIDATED import in the background: needs no unresolved duplicates, an unchanged file and mapping since the dry run, and no invalid rows unless skipInvalidRows. Poll until COMPLETED. After a FAILED commit the same call resumes")
	public ImportJobView commit(@PathVariable UUID schoolId, @PathVariable UUID importId,
			@RequestBody(required = false) @Valid CommitRequest r) {
		return this.imports.startCommit(CurrentUser.require(), schoolId, importId, (r != null) ? r.skipInvalidRows() : null);
	}

	@PostMapping("/{importId}/cancel")
	@Operation(operationId = "cancelImport", summary = "Cancel an import that was not committed yet; its rows are purged at once")
	public ImportJobView cancel(@PathVariable UUID schoolId, @PathVariable UUID importId) {
		return this.imports.cancel(CurrentUser.require(), schoolId, importId);
	}

	@DeleteMapping("/{importId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteImport", summary = "Delete an import with its rows and uploaded file at once (409 while it runs)")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID importId) {
		this.imports.delete(CurrentUser.require(), schoolId, importId);
	}

	public record CreateImportRequest(@NotNull ImportType importType, @NotNull @Schema(description = "A READY media of purpose IMPORT_FILE in this school") UUID mediaId,
			@Valid ImportOptions options, @Schema(description = "A saved mapping profile to apply; otherwise the newest matching profile or the header synonyms are used") UUID mappingProfileId) {
	}

	public record SaveMappingRequest(
			@NotNull @Schema(description = "Field key to zero-based column index") Map<String, Integer> columnMapping,
			@Valid ImportOptions options,
			@Size(max = 100, message = "validation.tooLong") @Schema(description = "Also save this mapping as a profile with this name") String saveAsProfile) {
	}

	public record ResolveRequest(@NotNull Resolution resolution,
			@Schema(description = "The existing student to link to (LINK only)") UUID studentId) {
	}

	public record CommitRequest(@Schema(description = "Apply the valid rows and leave the invalid ones out") Boolean skipInvalidRows) {
	}

}
