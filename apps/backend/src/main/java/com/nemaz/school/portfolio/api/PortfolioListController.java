package com.nemaz.school.portfolio.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioCapabilities;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemType;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioQueries;
import com.nemaz.school.portfolio.application.PortfolioQueries.Filter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portfolio lists: the student's portfolio, the caller's own items, the class review queue, the class and school galleries, a
 * guardian's combined view. All are filtered in SQL by the same predicate as the single read, keyset paged (newest first unless
 * stated), and carry media ids and variant names, never URLs.
 */
@RestController
@Tag(name = "portfolio")
public class PortfolioListController {

	private static final String SCHOOL = "/api/v1/schools/{schoolId}";

	private final PortfolioQueries queries;

	PortfolioListController(PortfolioQueries queries) {
		this.queries = queries;
	}

	@GetMapping(SCHOOL + "/students/{studentId}/portfolio")
	@Operation(operationId = "listStudentPortfolio",
			summary = "A student's portfolio: for the student, their own guardians (live link), their teachers, class managers and holders of school.portfolios.review; 404 student.not_found for everybody else. Each caller gets the items they may see (guardians: APPROVED and guardian-visible only). Filters status, itemType, classId, subjectId, featured")
	public CursorPage<PortfolioItemView> student(@PathVariable UUID schoolId, @PathVariable UUID studentId,
			@RequestParam(required = false) PortfolioStatus status, @RequestParam(required = false) PortfolioItemType itemType,
			@RequestParam(required = false) UUID classId, @RequestParam(required = false) UUID subjectId,
			@RequestParam(required = false) Boolean featured, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.studentPortfolio(CurrentUser.require(), schoolId, studentId,
				new Filter(status, itemType, classId, subjectId, featured), cursor, limit);
	}

	@GetMapping(SCHOOL + "/portfolio/mine")
	@Operation(operationId = "listMyPortfolioItems",
			summary = "The items the caller created or that are about them, in every status they may see (a student's drafts included)")
	public CursorPage<PortfolioItemView> mine(@PathVariable UUID schoolId, @RequestParam(required = false) PortfolioStatus status,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.mine(CurrentUser.require(), schoolId, status, cursor, limit);
	}

	@GetMapping(SCHOOL + "/classes/{classId}/portfolio/review-queue")
	@Operation(operationId = "listPortfolioReviewQueue",
			summary = "SUBMITTED items of the class, oldest first (the submission time), for its reviewers only (a subject teacher sees only their subject's items). 403 portfolio.review_not_allowed for other people who see the class, 404 for those who cannot")
	public CursorPage<PortfolioItemView> reviewQueue(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.reviewQueue(CurrentUser.require(), schoolId, classId, cursor, limit);
	}

	@GetMapping(SCHOOL + "/classes/{classId}/portfolio/gallery")
	@Operation(operationId = "listClassPortfolioGallery",
			summary = "APPROVED items of the class whose effective visibility is CLASS or SCHOOL, for the class's students and teachers (BASIC student data only, no guardian data). Empty unless the school raised portfolio.max_visibility")
	public CursorPage<PortfolioItemView> classGallery(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.classGallery(CurrentUser.require(), schoolId, classId, cursor, limit);
	}

	@GetMapping(SCHOOL + "/portfolio/gallery")
	@Operation(operationId = "listSchoolPortfolioGallery",
			summary = "APPROVED items whose effective visibility is SCHOOL, for the school's ACTIVE students and staff (guardians of other children never see it). Empty unless the school raised portfolio.max_visibility to SCHOOL")
	public CursorPage<PortfolioItemView> schoolGallery(@PathVariable UUID schoolId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.schoolGallery(CurrentUser.require(), schoolId, cursor, limit);
	}

	@GetMapping(SCHOOL + "/portfolio/capabilities")
	@Operation(operationId = "getPortfolioCapabilities",
			summary = "What the caller may do in the school's portfolios: create (a student), quick-capture (classes), review, the school cap, supported and deferred item types")
	public PortfolioCapabilities capabilities(@PathVariable UUID schoolId) {
		return this.queries.capabilities(CurrentUser.require(), schoolId);
	}

	@GetMapping("/api/v1/me/portfolio")
	@Operation(operationId = "listMyChildrenPortfolio",
			summary = "A guardian's combined view across their children and schools: APPROVED, guardian-visible items of ACTIVE, unrestricted links with academics, newest first; optional studentId (404 student.not_found when not the caller's child). Empty for everybody else")
	public CursorPage<PortfolioItemView> myChildren(@RequestParam(required = false) UUID studentId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.myChildren(CurrentUser.require(), studentId, cursor, limit);
	}

}
