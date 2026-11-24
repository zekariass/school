package com.nemaz.school.media;

import java.util.UUID;

/**
 * The media was deleted. Owning modules clear their references to it; the media module removes
 * its objects. Delivered at least once.
 */
public record MediaDeleted(UUID mediaId) {
}
