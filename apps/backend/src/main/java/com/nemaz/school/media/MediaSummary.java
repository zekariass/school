package com.nemaz.school.media;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * The compact form owning objects embed (a user's avatar, a school's logo). Clients ask
 * {@code GET /api/v1/media/{id}/url} for a fresh signed link when they show it.
 */
public record MediaSummary(@NotNull UUID id, @NotNull MediaKind kind, @NotNull String mimeType, Integer width,
		Integer height, @NotNull List<MediaVariant> variants) {
}
