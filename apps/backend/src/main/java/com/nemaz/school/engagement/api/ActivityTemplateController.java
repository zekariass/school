package com.nemaz.school.engagement.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.engagement.application.ActivityTemplateService;
import com.nemaz.school.engagement.application.ActivityTemplateService.TemplateInput;
import com.nemaz.school.engagement.application.ActivityTemplateService.TemplateItem;
import com.nemaz.school.engagement.application.ActivityTemplateService.TemplatePatch;
import com.nemaz.school.engagement.application.ActivityTemplateService.TemplateType;
import com.nemaz.school.engagement.application.ActivityTemplateService.TemplateView;
import com.nemaz.school.engagement.application.ActivityTemplateService.TemplateVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The teacher toolkit's routines, checklists and activity instructions (private, class or school wide). */
@RestController
@Tag(name = "toolkit")
public class ActivityTemplateController {

	private final ActivityTemplateService templates;

	ActivityTemplateController(ActivityTemplateService templates) {
		this.templates = templates;
	}

	@GetMapping("/api/v1/schools/{schoolId}/activity-templates")
	@Operation(operationId = "listActivityTemplates",
			summary = "The templates the caller may use: their own, those shared with a class they teach and the school-wide ones, newest first. Teachers and points managers; filters classId, templateType, mine")
	public CursorPage<TemplateView> list(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) TemplateType templateType,
			@RequestParam(required = false, defaultValue = "false") boolean mine,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.templates.list(CurrentUser.require(), schoolId, classId, templateType, mine, cursor, limit);
	}

	@PostMapping("/api/v1/schools/{schoolId}/activity-templates")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createActivityTemplate",
			summary = "Save a routine, checklist or activity (visibility PRIVATE by default; CLASS needs classId of a class the caller teaches; SCHOOL shares it with every teacher)")
	public TemplateView create(@PathVariable UUID schoolId, @Valid @RequestBody TemplateRequest r) {
		return this.templates.create(CurrentUser.require(), schoolId, new TemplateInput(r.templateType(), r.title(),
				r.instructions(), r.toItems(), r.visibility(), r.classId(), r.subjectId(), r.gradeLevelId()));
	}

	@GetMapping("/api/v1/schools/{schoolId}/activity-templates/{templateId}")
	@Operation(operationId = "getActivityTemplate", summary = "One template (404 when the caller may not see it)")
	public TemplateView get(@PathVariable UUID schoolId, @PathVariable UUID templateId) {
		return this.templates.get(CurrentUser.require(), schoolId, templateId);
	}

	@PatchMapping("/api/v1/schools/{schoolId}/activity-templates/{templateId}")
	@Operation(operationId = "updateActivityTemplate",
			summary = "Change a template (absent fields stay; items replace the list); its owner or a points manager")
	public TemplateView update(@PathVariable UUID schoolId, @PathVariable UUID templateId,
			@Valid @RequestBody TemplatePatchRequest r) {
		return this.templates.update(CurrentUser.require(), schoolId, templateId, new TemplatePatch(r.templateType(),
				r.title(), r.instructions(), r.toItems(), r.visibility(), r.classId(), r.subjectId(), r.gradeLevelId()));
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/activity-templates/{templateId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteActivityTemplate", summary = "Delete a template; its owner or a points manager")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID templateId) {
		this.templates.delete(CurrentUser.require(), schoolId, templateId);
	}

	public record ItemRequest(@NotBlank(message = "validation.required") @Size(max = 300, message = "validation.tooLong") String text,
			@Min(value = 0, message = "validation.invalid") @Max(value = 600, message = "validation.invalid") Integer minutes) {

		TemplateItem toItem() {
			return new TemplateItem(this.text, this.minutes);
		}

	}

	public record TemplateRequest(@NotNull(message = "validation.required") TemplateType templateType,
			@NotBlank(message = "validation.required") @Size(max = 200, message = "validation.tooLong") String title,
			@Size(max = 5000, message = "validation.tooLong") String instructions,
			@Size(max = 100, message = "validation.tooLong") List<@Valid @NotNull ItemRequest> items,
			TemplateVisibility visibility, UUID classId, UUID subjectId, UUID gradeLevelId) {

		List<TemplateItem> toItems() {
			return (this.items == null) ? List.of() : this.items.stream().map(ItemRequest::toItem).toList();
		}

	}

	public record TemplatePatchRequest(TemplateType templateType,
			@Size(min = 1, max = 200, message = "validation.tooLong") String title,
			@Size(max = 5000, message = "validation.tooLong") String instructions,
			@Size(max = 100, message = "validation.tooLong") List<@Valid @NotNull ItemRequest> items,
			TemplateVisibility visibility, UUID classId, UUID subjectId, UUID gradeLevelId) {

		List<TemplateItem> toItems() {
			return (this.items == null) ? null : this.items.stream().map(ItemRequest::toItem).toList();
		}

	}

}
