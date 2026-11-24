package com.nemaz.school.media;

import java.util.UUID;

/** The object media belongs to: media is read and managed through it (R-30). */
public record MediaContext(ContextType type, UUID id) {

	public MediaContext {
		if (type == null || id == null) {
			throw new IllegalArgumentException("A media context needs a type and an id");
		}
	}

	public static MediaContext user(UUID id) {
		return new MediaContext(ContextType.USER, id);
	}

	public static MediaContext school(UUID id) {
		return new MediaContext(ContextType.SCHOOL, id);
	}

	public static MediaContext organization(UUID id) {
		return new MediaContext(ContextType.ORGANIZATION, id);
	}

}
