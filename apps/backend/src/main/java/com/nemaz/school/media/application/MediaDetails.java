package com.nemaz.school.media.application;

import java.util.List;

import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.MediaVariantRow;

/** A media row with its stored variants. */
public record MediaDetails(Media media, List<MediaVariantRow> variants) {
}
