package com.nemaz.school.feed;

import java.util.UUID;

/**
 * The scheduler could not publish a due post (its consent gate failed): the post went back to DRAFT and its author is
 * told. {@code code} is the stable error code that blocked it.
 */
public record PostPublishBlocked(UUID postId, UUID schoolId, UUID classId, UUID authorId, String code) {
}
