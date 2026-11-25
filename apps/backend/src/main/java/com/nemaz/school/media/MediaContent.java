package com.nemaz.school.media;

import java.io.InputStream;

/** The stored original of a media object, opened for reading. The caller closes {@code stream}. */
public record MediaContent(String filename, String mimeType, long sizeBytes, InputStream stream) {
}
