package com.nemaz.school.media;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;

/** What owning modules use: attach media to their objects, show summaries, release media. */
public interface MediaService {

	/** The summary of READY media, empty for unknown, not-ready or deleted media. */
	Optional<MediaSummary> summary(UUID mediaId);

	/** Summaries of the READY media among {@code ids}. */
	Map<UUID, MediaSummary> summaries(Collection<UUID> ids);

	/**
	 * Checks that {@code mediaId} may be attached as {@code purpose} media of {@code context} by
	 * {@code user}: it exists, is READY, has that purpose and context, and the user uploaded it
	 * or manages the context. Otherwise throws a 404, 409 or 400 {@code ApiException}.
	 */
	MediaInfo requireAttachable(UUID mediaId, MediaPurpose purpose, MediaContext context, CurrentUser user);

	/**
	 * Opens the original bytes of READY media of any kind (the roster import reads its upload this way). Not an
	 * access check: the caller has made it through {@link #requireAttachable}. Empty for unknown or not-ready media.
	 */
	Optional<MediaContent> content(UUID mediaId);

	/** Deletes media that is no longer referenced (soft delete, then its objects are removed). Unknown ids are ignored. */
	void discard(UUID mediaId);

}
