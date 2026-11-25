package com.nemaz.school.engagement.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.engagement.application.BadgeService;
import com.nemaz.school.engagement.application.BadgeService.BadgeInput;
import com.nemaz.school.engagement.application.BadgeService.BadgePatch;
import com.nemaz.school.engagement.application.BadgeService.BadgeView;
import com.nemaz.school.engagement.application.BadgeService.StudentBadgeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

/** Badge definitions of a school and manual awards. The rule engine grants the others (see {@code BadgeService}). */
@RestController
@Tag(name = "badges")
public class BadgeController {

	private final BadgeService badges;

	BadgeController(BadgeService badges) {
		this.badges = badges;
	}

	@GetMapping("/api/v1/schools/{schoolId}/badges")
	@Operation(operationId = "listBadges",
			summary = "The school's badges (its copies of the default set and its own), in the caller's language; managers can include inactive ones. School members only")
	public List<BadgeView> list(@PathVariable UUID schoolId,
			@RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
		return this.badges.list(CurrentUser.require(), schoolId, includeInactive);
	}

	@PostMapping("/api/v1/schools/{schoolId}/badges")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createBadge",
			summary = "Define a badge (school.points.manage). criteria by type: POINTS_TOTAL {threshold, context?}, CATEGORY_TOTAL {threshold, categoryId or templateCode}, ATTENDANCE_STREAK {days} (granted by attendance, M7), MANUAL (none)")
	public BadgeView create(@PathVariable UUID schoolId, @Valid @RequestBody BadgeRequest r) {
		return this.badges.create(CurrentUser.require(), schoolId, new BadgeInput(r.code(), r.names(), r.descriptions(),
				r.icon(), r.criteriaType(), r.criteria(), r.sortOrder()));
	}

	@PatchMapping("/api/v1/schools/{schoolId}/badges/{badgeId}")
	@Operation(operationId = "updateBadge",
			summary = "Rename, change the rule's parameters or (de)activate a badge (school.points.manage); badges already granted stay")
	public BadgeView update(@PathVariable UUID schoolId, @PathVariable UUID badgeId,
			@Valid @RequestBody BadgePatchRequest r) {
		return this.badges.update(CurrentUser.require(), schoolId, badgeId, new BadgePatch(r.names(), r.descriptions(),
				r.icon(), r.criteria(), r.sortOrder(), r.active()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/students/{studentId}/badges")
	@Idempotent
	@Operation(operationId = "awardBadge",
			summary = "Hand a MANUAL badge to a student: their teachers and points managers. Repeating it returns the existing award; the student and their guardians are notified once. 409 points.badge_not_manual, points.badges_disabled")
	public StudentBadgeView award(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody AwardBadgeRequest r) {
		return this.badges.award(CurrentUser.require(), schoolId, studentId, r.badgeId());
	}

	@Schema(description = "names and descriptions: language code to text")
	public record BadgeRequest(
			@NotBlank(message = "validation.required") @Pattern(regexp = "^[a-z][a-z0-9_]{1,40}$", message = "validation.invalid") String code,
			@NotEmpty(message = "validation.required") @Size(max = 10, message = "validation.tooLong") Map<@Size(max = 8, message = "validation.invalid") String, @Size(max = 60, message = "validation.tooLong") String> names,
			@Size(max = 10, message = "validation.tooLong") Map<@Size(max = 8, message = "validation.invalid") String, @Size(max = 300, message = "validation.tooLong") String> descriptions,
			@Size(max = 64, message = "validation.tooLong") String icon,
			@NotBlank(message = "validation.required") String criteriaType,
			Map<String, Object> criteria, Integer sortOrder) {
	}

	public record BadgePatchRequest(
			@Size(max = 10, message = "validation.tooLong") Map<@Size(max = 8, message = "validation.invalid") String, @Size(max = 60, message = "validation.tooLong") String> names,
			@Size(max = 10, message = "validation.tooLong") Map<@Size(max = 8, message = "validation.invalid") String, @Size(max = 300, message = "validation.tooLong") String> descriptions,
			@Size(max = 64, message = "validation.tooLong") String icon, Map<String, Object> criteria, Integer sortOrder,
			Boolean active) {
	}

	public record AwardBadgeRequest(@NotNull(message = "validation.required") UUID badgeId) {
	}

}
