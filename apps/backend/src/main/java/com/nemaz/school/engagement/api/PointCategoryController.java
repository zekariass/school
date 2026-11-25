package com.nemaz.school.engagement.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.engagement.application.CategoryService;
import com.nemaz.school.engagement.application.CategoryService.CategoryInput;
import com.nemaz.school.engagement.application.CategoryService.CategoryPatch;
import com.nemaz.school.engagement.application.CategoryService.CategoryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point categories ("skills"). Reading: teachers of the class (with {@code classId}) or any ACTIVE teacher and points
 * manager of the school. School-level changes: {@code school.points.manage}. Class-level categories: the class's LEAD
 * or CO_TEACHER (and class managers, points managers). Categories are deactivated, never deleted.
 */
@RestController
@Tag(name = "points")
public class PointCategoryController {

	private final CategoryService categories;

	PointCategoryController(CategoryService categories) {
		this.categories = categories;
	}

	@GetMapping("/api/v1/schools/{schoolId}/point-categories")
	@Operation(operationId = "listPointCategories",
			summary = "The skills a teacher can award: the school's active CLASS categories plus, with classId, that class's own (teachers of the class); without classId for any teacher or points manager (managers also see HOME categories). NEEDS_WORK categories are left out while the school switched them off, unless an editor asks with includeInactive")
	public List<CategoryView> list(@PathVariable UUID schoolId, @RequestParam(required = false) UUID classId,
			@RequestParam(required = false) PointContext context,
			@RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
		return this.categories.list(CurrentUser.require(), schoolId, classId, context, includeInactive);
	}

	@PostMapping("/api/v1/schools/{schoolId}/point-categories")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createPointCategory",
			summary = "Add a school-level category (school.points.manage). context HOME makes a category for guardians (POSITIVE only)")
	public CategoryView create(@PathVariable UUID schoolId, @Valid @RequestBody CategoryRequest r) {
		return this.categories.create(CurrentUser.require(), schoolId, r.toInput());
	}

	@PatchMapping("/api/v1/schools/{schoolId}/point-categories/{categoryId}")
	@Operation(operationId = "updatePointCategory",
			summary = "Rename, recolor, reorder, change default points or (de)activate a school-level category (school.points.manage). Never deleted: the ledger refers to it")
	public CategoryView update(@PathVariable UUID schoolId, @PathVariable UUID categoryId,
			@Valid @RequestBody CategoryPatchRequest r) {
		return this.categories.update(CurrentUser.require(), schoolId, categoryId, r.toPatch());
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/point-categories")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createClassPointCategory",
			summary = "Add a category used in this class only (the class's LEAD or CO_TEACHER, class managers, points managers). 409 points.category_limit beyond 40 active ones")
	public CategoryView createForClass(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody CategoryRequest r) {
		return this.categories.createForClass(CurrentUser.require(), schoolId, classId, r.toInput());
	}

	@PatchMapping("/api/v1/schools/{schoolId}/classes/{classId}/point-categories/{categoryId}")
	@Operation(operationId = "updateClassPointCategory",
			summary = "Change or (de)activate one of the class's own categories (who may add them)")
	public CategoryView updateForClass(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@PathVariable UUID categoryId, @Valid @RequestBody CategoryPatchRequest r) {
		return this.categories.updateForClass(CurrentUser.require(), schoolId, classId, categoryId, r.toPatch());
	}

	@Schema(description = "names: language code to text, e.g. {\"en\": \"Kindness\", \"am\": \"...\"} (at least one)")
	public record CategoryRequest(
			@NotEmpty(message = "validation.required") @Size(max = 10, message = "validation.tooLong") Map<@Size(max = 8, message = "validation.invalid") String, @Size(max = 60, message = "validation.tooLong") String> names,
			@Size(max = 64, message = "validation.tooLong") String icon,
			@Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "validation.invalid") String color,
			@Min(value = 1, message = "validation.invalid") @Max(value = 10, message = "validation.invalid") Integer defaultPoints,
			PointPolarity polarity,
			@Schema(description = "CLASS (default) or HOME; class-level categories are always CLASS") PointContext context,
			@Min(value = 0, message = "validation.invalid") @Max(value = 10000, message = "validation.invalid") Integer sortOrder) {

		CategoryInput toInput() {
			return new CategoryInput(this.names, this.icon, this.color, this.defaultPoints, this.polarity, this.context,
					this.sortOrder);
		}

	}

	public record CategoryPatchRequest(
			@Size(max = 10, message = "validation.tooLong") Map<@Size(max = 8, message = "validation.invalid") String, @Size(max = 60, message = "validation.tooLong") String> names,
			@Size(max = 64, message = "validation.tooLong") String icon,
			@Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "validation.invalid") String color,
			@Min(value = 1, message = "validation.invalid") @Max(value = 10, message = "validation.invalid") Integer defaultPoints,
			@Min(value = 0, message = "validation.invalid") @Max(value = 10000, message = "validation.invalid") Integer sortOrder,
			Boolean active) {

		CategoryPatch toPatch() {
			return new CategoryPatch(this.names, this.icon, this.color, this.defaultPoints, this.sortOrder, this.active);
		}

	}

}
