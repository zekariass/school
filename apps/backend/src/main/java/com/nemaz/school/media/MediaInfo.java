package com.nemaz.school.media;

import java.util.UUID;

/** What a {@link MediaPurposePolicy} sees of a media row. */
public record MediaInfo(UUID id, UUID ownerId, MediaPurpose purpose, MediaContext context, UUID schoolId,
		MediaStatus status, MediaKind kind, String mimeType) {
}
