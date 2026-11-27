package com.nemaz.school.feed.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.application.FeedModels.FeedCapabilities;
import com.nemaz.school.feed.application.FeedModels.PostStatus;
import com.nemaz.school.feed.application.FeedModels.PostType;
import com.nemaz.school.feed.application.FeedModels.PostView;
import com.nemaz.school.feed.application.FeedQueries;
import com.nemaz.school.feed.application.FeedQueries.PostFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feeds: class feed, school feed and the family home feed. Media in a post is a list of media ids with variant names;
 * fetch a signed link through {@code GET /api/v1/media/{id}/url?variant=SMALL} (low-data clients use THUMB or SMALL).
 * All are keyset paged: pass the {@code nextCursor} of the previous page as {@code cursor}.
 */
@RestController
@Tag(name = "feed")
public class FeedController {

	private final FeedQueries queries;

	FeedController(FeedQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/feed")
	@Operation(operationId = "getClassFeed",
			summary = "The published posts of a class, pinned first then newest first; optional filters: `type` (POST, ACTIVITY, ACHIEVEMENT) and `photos=true` (only posts that carry media), applied after the visibility rule. Teachers and managers of the class, its ACTIVE students and guardians of ACTIVE students (not custody-restricted, with can_view_academics); 404 class.not_found for everybody else, 403 feed.feature_disabled when the school switched the feed off")
	public CursorPage<PostView> classFeed(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@RequestParam(required = false) PostType type, @RequestParam(required = false) Boolean photos,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.classFeed(CurrentUser.require(), schoolId, classId, PostFilter.of(type, photos), cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/feed")
	@Operation(operationId = "getSchoolFeed",
			summary = "The published school-wide posts, pinned first then newest first; optional filters `type` and `photos=true` as for a class. ACTIVE students, staff and guardians of ACTIVE students of the school, and school feed managers; 404 school.not_found for everybody else")
	public CursorPage<PostView> schoolFeed(@PathVariable UUID schoolId, @RequestParam(required = false) PostType type,
			@RequestParam(required = false) Boolean photos, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.schoolFeed(CurrentUser.require(), schoolId, PostFilter.of(type, photos), cursor, limit);
	}

	@GetMapping("/api/v1/me/feed")
	@Operation(operationId = "getMyFeed",
			summary = "The home feed of a guardian (or student): posts of the classes their children are ACTIVE in and school-wide posts of those children's schools, newest first, across schools. studentId narrows it to one child; optional filters `type` and `photos=true` as for a class (404 student.not_found when it is not the caller's child). Children behind a revoked, pending or custody-restricted link, or without can_view_academics, contribute nothing; photos withheld for consent are left out")
	public CursorPage<PostView> myFeed(@RequestParam(required = false) UUID studentId,
			@RequestParam(required = false) PostType type, @RequestParam(required = false) Boolean photos,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.queries.familyFeed(CurrentUser.require(), studentId, PostFilter.of(type, photos), cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/my-posts")
	@Operation(operationId = "listMyPosts",
			summary = "The caller's own posts and contributions in the school in every status (drafts, scheduled, pending approval, rejected, published, archived), newest first")
	public CursorPage<PostView> myPosts(@PathVariable UUID schoolId, @RequestParam(required = false) PostStatus status,
			@RequestParam(required = false) UUID classId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.myPosts(CurrentUser.require(), schoolId, status, classId, cursor, limit);
	}

	@GetMapping("/api/v1/schools/{schoolId}/feed/capabilities")
	@Operation(operationId = "getFeedCapabilities",
			summary = "What the caller may do in the feed of the school, or of one class when classId is given (for menus and buttons; the API stays the authority)")
	public FeedCapabilities capabilities(@PathVariable UUID schoolId,
			@Parameter(description = "A class of the school") @RequestParam(required = false) UUID classId) {
		return this.queries.capabilities(CurrentUser.require(), schoolId, classId);
	}

}
