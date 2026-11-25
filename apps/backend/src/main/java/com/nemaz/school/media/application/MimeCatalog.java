package com.nemaz.school.media.application;

import java.util.Locale;

import com.nemaz.school.media.MediaKind;

/** The MIME types the platform knows, and their kind. */
final class MimeCatalog {

	static final String JPEG = "image/jpeg";

	static final String PNG = "image/png";

	static final String WEBP = "image/webp";

	static final String GIF = "image/gif";

	static final String PDF = "application/pdf";

	static final String MP4 = "video/mp4";

	static final String MOV = "video/quicktime";

	static final String WEBM = "video/webm";

	static final String CSV = "text/csv";

	/** Some browsers and tools send a CSV as plain text or, on Windows, as the Excel type. */
	static final String TEXT_PLAIN = "text/plain";

	static final String MS_EXCEL = "application/vnd.ms-excel";

	static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private MimeCatalog() {
	}

	static MediaKind kind(String mimeType) {
		return switch (mimeType) {
			case JPEG, PNG, WEBP, GIF -> MediaKind.IMAGE;
			case MP4, MOV, WEBM -> MediaKind.VIDEO;
			case PDF -> MediaKind.DOCUMENT;
			default -> MediaKind.OTHER;
		};
	}

	/** Lower-cased MIME type without parameters ({@code "Image/JPEG; q=1"} becomes {@code "image/jpeg"}). */
	static String normalize(String contentType) {
		if (contentType == null) {
			return "";
		}
		int semicolon = contentType.indexOf(';');
		String base = (semicolon >= 0) ? contentType.substring(0, semicolon) : contentType;
		return base.trim().toLowerCase(Locale.ROOT);
	}

}
