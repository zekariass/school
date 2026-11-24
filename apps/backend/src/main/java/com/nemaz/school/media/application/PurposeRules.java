package com.nemaz.school.media.application;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;

/**
 * What each purpose accepts: the MIME types with their maximum size, and the kinds of object
 * it can belong to. This is the single table behind upload validation and
 * {@code GET /api/v1/media/policies}; adding a purpose's policy in its module is not enough
 * to use it until it has a row here.
 */
final class PurposeRules {

	static final long MB = 1024L * 1024L;

	static final long IMAGE_MAX = 10 * MB;

	static final long PDF_MAX = 25 * MB;

	static final long VIDEO_MAX = 100 * MB;

	/** Accepted MIME types of a purpose with their size limit in bytes, and the allowed contexts. */
	record Rule(Set<ContextType> contexts, Map<String, Long> maxBytesByMimeType) {

		long maxBytes(String mimeType) {
			return this.maxBytesByMimeType.getOrDefault(mimeType, 0L);
		}

		boolean allows(String mimeType) {
			return this.maxBytesByMimeType.containsKey(mimeType);
		}

	}

	private static final Map<MediaPurpose, Rule> RULES = new EnumMap<>(MediaPurpose.class);

	static {
		Map<String, Long> images = mimeLimits(IMAGE_MAX, MimeCatalog.JPEG, MimeCatalog.PNG, MimeCatalog.WEBP);
		RULES.put(MediaPurpose.AVATAR, new Rule(Set.of(ContextType.USER), images));
		RULES.put(MediaPurpose.BRANDING, new Rule(Set.of(ContextType.SCHOOL, ContextType.ORGANIZATION), images));
		// Purposes of later milestones: limits are fixed now, their policies arrive with their modules.
		Set<ContextType> school = Set.of(ContextType.SCHOOL);
		Map<String, Long> imagesAndVideo = merge(images, mimeLimits(VIDEO_MAX, MimeCatalog.MP4, MimeCatalog.MOV, MimeCatalog.WEBM));
		Map<String, Long> imagesAndPdf = merge(images, mimeLimits(PDF_MAX, MimeCatalog.PDF));
		Map<String, Long> everything = merge(imagesAndVideo, mimeLimits(PDF_MAX, MimeCatalog.PDF));
		RULES.put(MediaPurpose.POST, new Rule(school, everything));
		RULES.put(MediaPurpose.STORY, new Rule(school, imagesAndVideo));
		RULES.put(MediaPurpose.MESSAGE, new Rule(school, everything));
		RULES.put(MediaPurpose.PORTFOLIO, new Rule(school, everything));
		RULES.put(MediaPurpose.SUBMISSION, new Rule(school, everything));
		RULES.put(MediaPurpose.ASSIGNMENT, new Rule(school, imagesAndPdf));
		RULES.put(MediaPurpose.LIBRARY, new Rule(school, everything));
		RULES.put(MediaPurpose.COURSE, new Rule(school, everything));
		RULES.put(MediaPurpose.RECORDING, new Rule(school, mimeLimits(VIDEO_MAX, MimeCatalog.MP4, MimeCatalog.WEBM)));
		RULES.put(MediaPurpose.TRANSCRIPT, new Rule(school, mimeLimits(PDF_MAX, MimeCatalog.PDF)));
		RULES.put(MediaPurpose.VERIFICATION_DOC, new Rule(Set.of(ContextType.USER), imagesAndPdf));
		RULES.put(MediaPurpose.IMPORT_FILE, new Rule(school, mimeLimits(PDF_MAX, MimeCatalog.CSV, MimeCatalog.XLSX)));
		RULES.put(MediaPurpose.REPORT, new Rule(school, mimeLimits(PDF_MAX, MimeCatalog.PDF)));
	}

	private PurposeRules() {
	}

	static Rule of(MediaPurpose purpose) {
		return RULES.get(purpose);
	}

	static MediaKind kindOf(String mimeType) {
		return MimeCatalog.kind(mimeType);
	}

	private static Map<String, Long> mimeLimits(long max, String... mimeTypes) {
		Map<String, Long> map = new LinkedHashMap<>();
		for (String mime : mimeTypes) {
			map.put(mime, max);
		}
		return map;
	}

	private static Map<String, Long> merge(Map<String, Long> a, Map<String, Long> b) {
		Map<String, Long> map = new LinkedHashMap<>(a);
		map.putAll(b);
		return map;
	}

}
