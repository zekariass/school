package com.nemaz.school.media.application;

import java.util.List;

import com.nemaz.school.media.MediaDeleted;
import com.nemaz.school.media.MediaUploaded;
import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.MediaVariantRow;
import com.nemaz.school.media.domain.ObjectStorage;
import com.nemaz.school.media.infrastructure.MediaRepository;
import com.nemaz.school.media.infrastructure.MediaVariantRepository;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Reacts to the media module's own events: processing after an upload, object removal after a delete. */
@Component
class MediaEvents {

	private final MediaProcessor processor;

	private final MediaRepository media;

	private final MediaVariantRepository variants;

	private final ObjectStorage storage;

	MediaEvents(MediaProcessor processor, MediaRepository media, MediaVariantRepository variants,
			ObjectStorage storage) {
		this.processor = processor;
		this.media = media;
		this.variants = variants;
		this.storage = storage;
	}

	@ApplicationModuleListener
	void on(MediaUploaded event) {
		this.processor.process(event.mediaId());
	}

	/** Deleting an object that is already gone is not an error, so redelivery is harmless. */
	@ApplicationModuleListener
	void on(MediaDeleted event) {
		Media m = this.media.findById(event.mediaId()).orElse(null);
		if (m == null) {
			return;
		}
		List<MediaVariantRow> rows = this.variants.findByMediaIdOrderByVariant(m.getId());
		rows.forEach((v) -> this.storage.delete(v.getObjectKey()));
		this.storage.delete(m.getObjectKey());
	}

}
