package com.nemaz.school.media;

import java.util.UUID;

/** The object passed the size, type and magic-byte checks; processing starts. Delivered at least once. */
public record MediaUploaded(UUID mediaId) {
}
