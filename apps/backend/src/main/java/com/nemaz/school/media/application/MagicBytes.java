package com.nemaz.school.media.application;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Content sniffing by signature: what a file really is, whatever the client declared. Used at
 * completion on the first 4 KB of the object. Only signatures of types the platform accepts
 * are known; anything else yields an empty set and is rejected.
 */
final class MagicBytes {

	/** What delimited text may be declared as: clients differ (text/csv, text/plain, the Excel type with a .csv name). */
	private static final Set<String> TEXT_TYPES = Set.of(MimeCatalog.CSV, MimeCatalog.TEXT_PLAIN, MimeCatalog.MS_EXCEL);

	/** Bytes the sniffer needs. */
	static final int SNIFF_LENGTH = 4096;

	private MagicBytes() {
	}

	/** The accepted MIME types this content can be (several for container formats), empty when unrecognised. */
	static Set<String> detect(byte[] head) {
		if (head == null || head.length < 4) {
			return Set.of();
		}
		if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
			return Set.of(MimeCatalog.JPEG);
		}
		if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
			return Set.of(MimeCatalog.PNG);
		}
		if (head.length >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) {
			return Set.of(MimeCatalog.WEBP);
		}
		if (ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a")) {
			return Set.of(MimeCatalog.GIF);
		}
		if (ascii(head, 0, "%PDF-")) {
			return Set.of(MimeCatalog.PDF);
		}
		if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) {
			return Set.of(MimeCatalog.WEBM);
		}
		if (head.length >= 12 && ascii(head, 4, "ftyp")) {
			// ISO base media: the major brand tells QuickTime ("qt  ") from MP4 (isom, mp42, ...).
			return ascii(head, 8, "qt  ") ? Set.of(MimeCatalog.MOV) : Set.of(MimeCatalog.MP4);
		}
		if (head.length >= 8 && (ascii(head, 4, "moov") || ascii(head, 4, "mdat") || ascii(head, 4, "wide"))) {
			return Set.of(MimeCatalog.MOV);
		}
		if (startsWith(head, 0x50, 0x4B, 0x03, 0x04)) {
			return Set.of(MimeCatalog.XLSX);
		}
		if (isPlainText(head)) {
			return TEXT_TYPES;
		}
		return Set.of();
	}

	/** Text has no signature: it is plain text when there is no NUL or other control byte (tab, CR and LF are fine). */
	private static boolean isPlainText(byte[] head) {
		if (startsWith(head, 0xFF, 0xFE) || startsWith(head, 0xFE, 0xFF)) {
			return isUtf16Text(head);
		}
		for (byte b : head) {
			int v = b & 0xFF;
			if (v == 0 || (v < 0x20 && v != '\t' && v != '\r' && v != '\n')) {
				return false;
			}
		}
		return true;
	}

	/** Excel's "Unicode Text" export: a UTF-16 byte order mark and then characters without control codes (tab, CR, LF aside). */
	private static boolean isUtf16Text(byte[] head) {
		boolean littleEndian = (head[0] & 0xFF) == 0xFF;
		int end = head.length - ((head.length - 2) % 2);
		for (int i = 2; i + 1 < end; i += 2) {
			int unit = littleEndian ? ((head[i] & 0xFF) | ((head[i + 1] & 0xFF) << 8))
					: (((head[i] & 0xFF) << 8) | (head[i + 1] & 0xFF));
			if (unit < 0x20 && unit != '\t' && unit != '\r' && unit != '\n') {
				return false;
			}
		}
		return true;
	}

	private static boolean startsWith(byte[] data, int... signature) {
		if (data.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if ((data[i] & 0xFF) != signature[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean ascii(byte[] data, int offset, String text) {
		byte[] expected = text.getBytes(StandardCharsets.US_ASCII);
		if (data.length < offset + expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if (data[offset + i] != expected[i]) {
				return false;
			}
		}
		return true;
	}

}
