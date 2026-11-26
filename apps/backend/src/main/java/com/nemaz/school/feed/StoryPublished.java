package com.nemaz.school.feed;

import java.util.UUID;

/** A story was published (it expires 24 hours later). {@code classId} is null for a school story. */
public record StoryPublished(UUID storyId, UUID schoolId, UUID classId, UUID authorId) {
}
