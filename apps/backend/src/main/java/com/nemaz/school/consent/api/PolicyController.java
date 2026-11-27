package com.nemaz.school.consent.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.consent.application.PolicyService;
import com.nemaz.school.consent.application.PolicyService.Document;
import com.nemaz.school.consent.application.PolicyService.PolicyView;
import com.nemaz.school.consent.application.PolicyService.SchoolPolicy;
import com.nemaz.school.consent.application.PolicyService.Version;
import com.nemaz.school.consent.application.PolicyService.VersionChange;
import com.nemaz.school.consent.application.PolicyService.VersionDraft;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy texts: the public read of the current text, and a school's own wording ({@code school.policies.manage}) for
 * ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION. Published versions never change.
 */
@RestController
@Tag(name = "policies")
public class PolicyController {

	private final PolicyService policies;

	PolicyController(PolicyService policies) {
		this.policies = policies;
	}

	@GetMapping("/api/v1/policies/{policyType}")
	@Operation(operationId = "getPolicy",
			summary = "The current text of a policy (public): the school's own document if it has a published version, else the default of the country (the `country` parameter, else the platform default country); legal text of another country is never served: 404 policy.country_not_available when the resolved country has none; in lang, else English, else any language. 404 policy.not_found")
	public PolicyView read(@PathVariable ConsentType policyType, @RequestParam(required = false) UUID schoolId,
			@RequestParam(required = false) @Pattern(regexp = "^[A-Za-z]{2}$") String country,
			@RequestParam(required = false) @Size(max = 8) String lang) {
		return this.policies.read(policyType, schoolId, country, lang);
	}

	@GetMapping("/api/v1/schools/{schoolId}/policies")
	@Operation(operationId = "listSchoolPolicies", summary = "The school's own policy documents with every version (drafts included)")
	public List<SchoolPolicy> list(@PathVariable UUID schoolId) {
		return this.policies.list(CurrentUser.require(), schoolId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/policies")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createSchoolPolicy",
			summary = "Start the school's own wording of ACCOUNT_PARTICIPATION or MEDIA_PUBLICATION (409 policy.already_exists); it replaces the country default once a version is published")
	public Document create(@PathVariable UUID schoolId, @Valid @RequestBody CreatePolicyRequest r) {
		return this.policies.createDocument(CurrentUser.require(), schoolId, r.policyType(), r.title());
	}

	@PutMapping("/api/v1/schools/{schoolId}/policies/{documentId}")
	@Operation(operationId = "updateSchoolPolicy", summary = "Rename a school policy document")
	public Document update(@PathVariable UUID schoolId, @PathVariable UUID documentId,
			@Valid @RequestBody UpdatePolicyRequest r) {
		return this.policies.updateDocument(CurrentUser.require(), schoolId, documentId, r.title());
	}

	@PostMapping("/api/v1/schools/{schoolId}/policies/{documentId}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createSchoolPolicyVersion",
			summary = "Add a draft version in one language (one row per label and language; 409 policy.version_exists)")
	public Version addVersion(@PathVariable UUID schoolId, @PathVariable UUID documentId,
			@Valid @RequestBody CreateVersionRequest r) {
		return this.policies.addVersion(CurrentUser.require(), schoolId, documentId, new VersionDraft(r.versionLabel(),
				r.languageCode(), r.content(), r.effectiveFrom(), r.requiresReconsent()));
	}

	@PutMapping("/api/v1/schools/{schoolId}/policies/{documentId}/versions/{versionId}")
	@Operation(operationId = "updateSchoolPolicyVersion",
			summary = "Change a draft version (409 policy.version_published once published)")
	public Version updateVersion(@PathVariable UUID schoolId, @PathVariable UUID documentId,
			@PathVariable UUID versionId, @Valid @RequestBody UpdateVersionRequest r) {
		return this.policies.updateVersion(CurrentUser.require(), schoolId, documentId, versionId,
				new VersionChange(r.content(), r.effectiveFrom(), r.requiresReconsent()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/policies/{documentId}/versions/{versionId}/publish")
	@Operation(operationId = "publishSchoolPolicyVersion",
			summary = "Publish a draft: it becomes immutable and takes effect at effectiveFrom (now at the earliest). With requiresReconsent, guardians are asked again and earlier grants stop counting once it is in effect")
	public Version publish(@PathVariable UUID schoolId, @PathVariable UUID documentId, @PathVariable UUID versionId) {
		return this.policies.publish(CurrentUser.require(), schoolId, documentId, versionId);
	}

	public record CreatePolicyRequest(@NotNull(message = "validation.required") ConsentType policyType,
			@NotBlank(message = "validation.required") @Size(max = 200, message = "validation.tooLong") String title) {
	}

	public record UpdatePolicyRequest(
			@NotBlank(message = "validation.required") @Size(max = 200, message = "validation.tooLong") String title) {
	}

	public record CreateVersionRequest(
			@NotBlank(message = "validation.required") @Size(max = 40, message = "validation.tooLong") String versionLabel,
			@NotBlank(message = "validation.required") @Size(max = 8, message = "validation.tooLong") String languageCode,
			@NotBlank(message = "validation.required") @Size(max = 20000, message = "validation.tooLong") String content,
			Instant effectiveFrom, Boolean requiresReconsent) {
	}

	public record UpdateVersionRequest(@Size(min = 1, max = 20000, message = "validation.tooLong") String content,
			Instant effectiveFrom, Boolean requiresReconsent) {
	}

}
