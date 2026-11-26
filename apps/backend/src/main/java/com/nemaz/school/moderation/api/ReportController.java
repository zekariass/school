package com.nemaz.school.moderation.api;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.moderation.application.EmergencyInfoService;
import com.nemaz.school.moderation.application.ModerationModels.EmergencyInfoView;
import com.nemaz.school.moderation.application.ModerationModels.ReportReceipt;
import com.nemaz.school.moderation.application.ModerationModels.ReportRequest;
import com.nemaz.school.moderation.application.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reporting (docs/Safeguarding_Rules.md S1): any signed-in person, students included, and the safety text of the screens. */
@RestController
@Tag(name = "moderation")
public class ReportController {

	private final ReportService reports;

	private final EmergencyInfoService emergency;

	ReportController(ReportService reports, EmergencyInfoService emergency) {
		this.reports = reports;
		this.emergency = emergency;
	}

	@PostMapping("/api/v1/moderation/reports")
	@Idempotent
	@Operation(operationId = "createModerationReport", summary = """
			Report something you can see: a post, a comment, a story, a message, a person (shared class, school or conversation). \
			A target you cannot see answers the same generic 404 (moderation.target_not_found) as the content itself, so a \
			custody-restricted or revoked guardian cannot report it. 201 for a new report; 200 with the first report when you already \
			have an open report of the same target. At most 20 reports a day, 10 for students. You get one thank-you notice now and \
			one 'reviewed' notice when the case is resolved: never an outcome, names or content. Details (up to 1,000 characters) are \
			plain text and are never copied into events, logs, audit rows or notifications.""")
	public ResponseEntity<ReportReceipt> report(@Valid @RequestBody ReportRequest request) {
		ReportService.Result result = this.reports.file(CurrentUser.require(), request);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.receipt());
	}

	@GetMapping("/api/v1/safety/emergency-info")
	@Operation(operationId = "getEmergencyInfo", summary = """
			The country's safety text for the reporting screens ('if a child is in danger contact ...'), in your language. \
			reviewed false means the wording is a placeholder the owner has not approved; the Ethiopian default is exactly that.""")
	public EmergencyInfoView emergencyInfo(@RequestParam String country) {
		return this.emergency.forCountry(country);
	}

}
