package com.nemaz.school.people.api;

import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.people.application.SchoolSummaryService;
import com.nemaz.school.people.application.SchoolSummaryService.SchoolCounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The numbers behind a school administrator's dashboard. */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/summary")
@Tag(name = "school-summary")
public class SchoolSummaryController {

	private final SchoolSummaryService summaries;

	SchoolSummaryController(SchoolSummaryService summaries) {
		this.summaries = summaries;
	}

	@GetMapping
	@Operation(operationId = "getSchoolSummary",
			summary = "Counts of active students, teachers and classes plus what is already set up (current year, grade levels, subjects); needs school.read")
	public SchoolCounts get(@PathVariable UUID schoolId) {
		return this.summaries.summary(CurrentUser.require(), schoolId);
	}

}
