package com.nemaz.school.imports.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.imports.application.ImportTemplates;
import com.nemaz.school.imports.application.MappingProfileService;
import com.nemaz.school.imports.application.MappingProfileService.ProfileView;
import com.nemaz.school.imports.domain.ImportType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Templates and saved column mappings of a school's imports. */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/imports")
@Tag(name = "imports")
public class ImportSupportController {

	private final ImportTemplates templates;

	private final MappingProfileService profiles;

	ImportSupportController(ImportTemplates templates, MappingProfileService profiles) {
		this.templates = templates;
		this.profiles = profiles;
	}

	@GetMapping(value = "/templates/{importType}", produces = { "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
	@Operation(operationId = "downloadImportTemplate",
			summary = "A template for the import type: header row of machine keys, an example row starting with # (ignored), instructions in English and Amharic (xlsx also has lists of the school's classes, grade levels and campuses)")
	public ResponseEntity<byte[]> template(@PathVariable UUID schoolId, @PathVariable ImportType importType,
			@RequestParam(defaultValue = "csv") String format) {
		ImportTemplates.Template template = this.templates.template(CurrentUser.require(), schoolId, importType, format);
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(template.filename()).build().toString())
			.cacheControl(CacheControl.noStore().cachePrivate())
			.contentType(MediaType.parseMediaType(template.contentType()))
			.body(template.content());
	}

	@GetMapping("/mapping-profiles")
	@Operation(operationId = "listImportMappingProfiles", summary = "Saved column mappings, optionally of one import type")
	public List<ProfileView> list(@PathVariable UUID schoolId, @RequestParam(required = false) ImportType importType) {
		return this.profiles.list(CurrentUser.require(), schoolId, importType);
	}

	@PostMapping("/mapping-profiles")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createImportMappingProfile",
			summary = "Save a column mapping (field key to header text) for an import type; 409 import.profile_name_taken")
	public ProfileView create(@PathVariable UUID schoolId, @Valid @RequestBody CreateProfileRequest r) {
		return this.profiles.create(CurrentUser.require(), schoolId, r.name(), r.importType(), r.columnMapping());
	}

	@PutMapping("/mapping-profiles/{profileId}")
	@Operation(operationId = "updateImportMappingProfile", summary = "Rename a profile or replace its mapping")
	public ProfileView update(@PathVariable UUID schoolId, @PathVariable UUID profileId,
			@Valid @RequestBody UpdateProfileRequest r) {
		return this.profiles.update(CurrentUser.require(), schoolId, profileId, r.name(), r.columnMapping());
	}

	@DeleteMapping("/mapping-profiles/{profileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteImportMappingProfile", summary = "Delete a profile (imports that used it keep their mapping)")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID profileId) {
		this.profiles.delete(CurrentUser.require(), schoolId, profileId);
	}

	public record CreateProfileRequest(@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@NotNull ImportType importType,
			@NotNull @Schema(description = "Field key to the header text of the column in the school's files") Map<String, String> columnMapping) {
	}

	public record UpdateProfileRequest(@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@NotNull Map<String, String> columnMapping) {
	}

}
