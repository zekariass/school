package com.nemaz.school.engagement.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.engagement.application.AwardService;
import com.nemaz.school.engagement.application.AwardService.AwardCommand;
import com.nemaz.school.engagement.application.AwardService.AwardResult;
import com.nemaz.school.engagement.application.AwardService.ReverseCommand;
import com.nemaz.school.engagement.application.CategoryService;
import com.nemaz.school.engagement.application.CategoryService.CategoryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Awarding and undoing points. {@code clientOpId} (a UUID the client makes once per user action and keeps in its
 * offline queue) makes every call safe to repeat: the same id returns the original rows with status 200 and
 * {@code replayed: true}, a first call is 201. The {@code Idempotency-Key} header works on top of it (HTTP-level
 * replay); use {@code clientOpId} for anything queued offline, because it survives the 24 hours of that header.
 */
@RestController
@Tag(name = "points")
public class PointAwardController {

	private final AwardService awards;

	private final CategoryService categories;

	PointAwardController(AwardService awards, CategoryService categories) {
		this.awards = awards;
		this.categories = categories;
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/points/awards")
	@Idempotent
	@Operation(operationId = "awardClassPoints",
			summary = "Award points to one student, several (studentIds) or the whole class (wholeClass: true) in a category of the school or of the class. Teachers of the class (ACTIVE) and class managers; the students must be ACTIVE in the class; NEEDS_WORK categories only while the school allows them. Several students share one batchId. 201 new, 200 replay of a clientOpId")
	public ResponseEntity<AwardResult> awardInClass(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody AwardRequest r) {
		return respond(this.awards.awardInClass(CurrentUser.require(), schoolId, classId, r.toCommand()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/points/awards")
	@Idempotent
	@Operation(operationId = "awardSchoolPoints",
			summary = "Award points school-wide (SCHOOL context, school-level categories) to ACTIVE students of the school (school.points.manage)")
	public ResponseEntity<AwardResult> awardInSchool(@PathVariable UUID schoolId, @Valid @RequestBody AwardRequest r) {
		return respond(this.awards.awardInSchool(CurrentUser.require(), schoolId, r.toCommand()));
	}

	@GetMapping("/api/v1/schools/{schoolId}/students/{studentId}/home-point-categories")
	@Operation(operationId = "listHomePointCategories",
			summary = "The HOME categories a guardian may award to their own child: an ACTIVE, not custody-restricted link with can_award_points (404 for anybody else linked to nothing, 403 without the flag)")
	public List<CategoryView> homeCategories(@PathVariable UUID schoolId, @PathVariable UUID studentId) {
		this.awards.requireHomeAwarder(CurrentUser.require(), schoolId, studentId);
		return this.categories.homeCategories(schoolId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/students/{studentId}/home-points")
	@Idempotent
	@Operation(operationId = "awardHomePoints",
			summary = "A guardian awards home points to their own child (HOME context, HOME category, never counted in class or school totals). Needs an ACTIVE, not custody-restricted link with can_award_points; at most 60 a day. 201 new, 200 replay of a clientOpId")
	public ResponseEntity<AwardResult> awardAtHome(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@Valid @RequestBody AwardRequest r) {
		return respond(this.awards.awardAtHome(CurrentUser.require(), schoolId, studentId, r.toCommand()));
	}

	@PostMapping("/api/v1/schools/{schoolId}/points/transactions/{transactionId}/reverse")
	@Idempotent
	@Operation(operationId = "reversePointTransaction",
			summary = "Undo one award with a REVERSAL row. The awarder within the undo window (engagement.points.undo_window_seconds, default 120), the class's lead teacher, class managers and points managers any time; home points only by the guardian who awarded them, inside the window. 409 points.already_reversed, 403 points.undo_window_expired")
	public ResponseEntity<AwardResult> reverse(@PathVariable UUID schoolId, @PathVariable UUID transactionId,
			@Valid @RequestBody(required = false) ReverseRequest r) {
		return respond(this.awards.reverse(CurrentUser.require(), schoolId, transactionId, command(r)));
	}

	@PostMapping("/api/v1/schools/{schoolId}/points/batches/{batchId}/reverse")
	@Idempotent
	@Operation(operationId = "reversePointBatch",
			summary = "Undo a whole multi-student or whole-class award (every row not reversed yet), with the same rules as reversing one transaction")
	public ResponseEntity<AwardResult> reverseBatch(@PathVariable UUID schoolId, @PathVariable UUID batchId,
			@Valid @RequestBody(required = false) ReverseRequest r) {
		return respond(this.awards.reverseBatch(CurrentUser.require(), schoolId, batchId, command(r)));
	}

	private static ReverseCommand command(ReverseRequest r) {
		return (r != null) ? new ReverseCommand(r.note(), r.clientOpId()) : new ReverseCommand(null, null);
	}

	private static ResponseEntity<AwardResult> respond(AwardResult result) {
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
	}

	@Schema(description = "Exactly one of studentIds or wholeClass: true (home awards ignore both: the student is in the path)")
	public record AwardRequest(@NotNull(message = "validation.required") UUID categoryId,
			@Size(max = 500, message = "validation.tooLong") List<@NotNull UUID> studentIds, Boolean wholeClass,
			@Min(value = 1, message = "validation.invalid") @Max(value = 10, message = "validation.invalid") @Schema(description = "1 to 10; defaults to the category's default points. The sign comes from the category") Integer points,
			@Size(max = 500, message = "validation.tooLong") @Schema(description = "A note (child data: shown only to those who may see the award)") String note,
			@Schema(description = "Client-made id of this user action; repeating it never awards twice") UUID clientOpId) {

		AwardCommand toCommand() {
			return new AwardCommand(this.categoryId, this.studentIds, this.wholeClass, this.points, this.note,
					this.clientOpId);
		}

		@Override
		public String toString() {
			return "AwardRequest[redacted]";
		}

	}

	public record ReverseRequest(@Size(max = 500, message = "validation.tooLong") String note, UUID clientOpId) {

		@Override
		public String toString() {
			return "ReverseRequest[redacted]";
		}

	}

}
