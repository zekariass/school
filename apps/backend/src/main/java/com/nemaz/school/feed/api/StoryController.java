package com.nemaz.school.feed.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.feed.application.FeedModels.StoryView;
import com.nemaz.school.feed.application.StoryService;
import com.nemaz.school.feed.application.StoryService.StoryCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Class and school stories: a photo and/or a short text visible for 24 hours. Nobody sees a story after its
 * {@code expiresAt}, whether or not the expiry job has run.
 */
@RestController
@Tag(name = "feed")
public class StoryController {

	private final StoryService stories;

	StoryController(StoryService stories) {
		this.stories = stories;
	}

	@PostMapping("/api/v1/schools/{schoolId}/classes/{classId}/stories")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createClassStory",
			summary = "Publish a class story (teachers of the class and class managers): a STORY photo uploaded for the class and/or a caption. Same consent gate as posts")
	public StoryView createClassStory(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody StoryCreateRequest r) {
		return this.stories.createClassStory(CurrentUser.require(), schoolId, classId, r.toCommand());
	}

	@PostMapping("/api/v1/schools/{schoolId}/stories")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createSchoolStory", summary = "Publish a school story (school.feed.manage). Same consent gate as posts")
	public StoryView createSchoolStory(@PathVariable UUID schoolId, @Valid @RequestBody StoryCreateRequest r) {
		return this.stories.createSchoolStory(CurrentUser.require(), schoolId, r.toCommand());
	}

	@GetMapping("/api/v1/schools/{schoolId}/classes/{classId}/stories")
	@Operation(operationId = "listClassStories",
			summary = "The active (not expired) stories of a class, newest first, to those who may see the class feed")
	public List<StoryView> classStories(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.stories.classStories(CurrentUser.require(), schoolId, classId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/stories")
	@Operation(operationId = "listSchoolStories", summary = "The active school stories, newest first, to the members and guardians of the school")
	public List<StoryView> schoolStories(@PathVariable UUID schoolId) {
		return this.stories.schoolStories(CurrentUser.require(), schoolId);
	}

	@GetMapping("/api/v1/me/stories")
	@Operation(operationId = "listMyStories",
			summary = "Active stories of the classes and schools of the caller's children (or of the caller, a student), newest first; studentId narrows it to one child")
	public List<StoryView> myStories(@RequestParam(required = false) UUID studentId) {
		return this.stories.familyStories(CurrentUser.require(), studentId);
	}

	@GetMapping("/api/v1/schools/{schoolId}/stories/{storyId}")
	@Operation(operationId = "getStory", summary = "One story: while active to those who may see it; to its author and moderators until it is deleted")
	public StoryView story(@PathVariable UUID schoolId, @PathVariable UUID storyId) {
		return this.stories.story(CurrentUser.require(), schoolId, storyId);
	}

	@PostMapping("/api/v1/schools/{schoolId}/stories/{storyId}/view")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "markStoryViewed", summary = "Record that the caller saw the story (once per viewer; only the count is ever shown, to the author and staff)")
	public void view(@PathVariable UUID schoolId, @PathVariable UUID storyId) {
		this.stories.view(CurrentUser.require(), schoolId, storyId);
	}

	@DeleteMapping("/api/v1/schools/{schoolId}/stories/{storyId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteStory", summary = "Delete a story and its photo (the author, or a moderator; audited)")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID storyId) {
		this.stories.delete(CurrentUser.require(), schoolId, storyId);
	}

	public record StoryCreateRequest(@Size(max = 500, message = "validation.tooLong") String caption,
			@Size(max = 8, message = "validation.tooLong") String captionLanguageCode, UUID mediaId,
			@Size(max = 50, message = "validation.tooMany") List<@NotNull UUID> studentIds,
			Boolean confirmNoUnconsentedStudents) {

		StoryCommand toCommand() {
			return new StoryCommand(this.caption, this.captionLanguageCode, this.mediaId, this.studentIds,
					Boolean.TRUE.equals(this.confirmNoUnconsentedStudents));
		}

		@Override
		public String toString() {
			return "StoryCreateRequest[redacted]";
		}

	}

}
