package com.nemaz.school.imports.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Finds out how a CSV file is encoded and delimited, without a detector library (they guess badly on short samples):
 * <ol>
 * <li>a byte order mark (UTF-8, UTF-16LE, UTF-16BE);</li>
 * <li>alternating zero bytes mean UTF-16 without a mark (Excel's "Unicode Text" has the mark, other tools do not);</li>
 * <li>a strict UTF-8 decode of the whole file succeeds: UTF-8;</li>
 * <li>otherwise windows-1252 (cannot hold Ethiopic; a file in it is a Latin-script file).</li>
 * </ol>
 * The delimiter is the {@code sep=;} line Excel understands, else the candidate that splits the first lines evenly
 * outside quotes.
 */
final class CsvDetector {

	static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

	private static final char[] CANDIDATES = { ',', ';', '\t', '|' };

	private CsvDetector() {
	}

	/** {@code bomLength} bytes at the start belong to the byte order mark. */
	record Encoding(Charset charset, int bomLength, String label) {
	}

	static Encoding detectEncoding(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
			return new Encoding(StandardCharsets.UTF_8, 3, "UTF-8");
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
			return new Encoding(StandardCharsets.UTF_16LE, 2, "UTF-16LE");
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
			return new Encoding(StandardCharsets.UTF_16BE, 2, "UTF-16BE");
		}
		int sample = Math.min(bytes.length, 4096);
		int evenZeros = 0;
		int oddZeros = 0;
		for (int i = 0; i < sample; i++) {
			if (bytes[i] == 0) {
				if (i % 2 == 0) {
					evenZeros++;
				}
				else {
					oddZeros++;
				}
			}
		}
		int half = sample / 2;
		if (half >= 4 && oddZeros > half * 0.3 && evenZeros < half * 0.1) {
			return new Encoding(StandardCharsets.UTF_16LE, 0, "UTF-16LE");
		}
		if (half >= 4 && evenZeros > half * 0.3 && oddZeros < half * 0.1) {
			return new Encoding(StandardCharsets.UTF_16BE, 0, "UTF-16BE");
		}
		if (decodesStrictly(bytes, 0, StandardCharsets.UTF_8)) {
			return new Encoding(StandardCharsets.UTF_8, 0, "UTF-8");
		}
		return new Encoding(WINDOWS_1252, 0, "windows-1252");
	}

	/** The encoding the user chose, or null when the label is not one we offer. */
	static Encoding forLabel(String label, byte[] bytes) {
		Encoding detected = detectEncoding(bytes);
		if (label == null) {
			return detected;
		}
		return switch (label.strip().toLowerCase(java.util.Locale.ROOT)) {
			case "utf-8", "utf8" -> new Encoding(StandardCharsets.UTF_8, (detected.charset() == StandardCharsets.UTF_8) ? detected.bomLength() : 0, "UTF-8");
			case "utf-16le", "utf-16" -> new Encoding(StandardCharsets.UTF_16LE, startsWithBom(bytes, 0xFF, 0xFE) ? 2 : 0, "UTF-16LE");
			case "utf-16be" -> new Encoding(StandardCharsets.UTF_16BE, startsWithBom(bytes, 0xFE, 0xFF) ? 2 : 0, "UTF-16BE");
			case "windows-1252", "cp1252", "latin1", "iso-8859-1" -> new Encoding(WINDOWS_1252, 0, "windows-1252");
			default -> null;
		};
	}

	static boolean decodesStrictly(byte[] bytes, int offset, Charset charset) {
		try {
			charset.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
			return true;
		}
		catch (CharacterCodingException ex) {
			return false;
		}
	}

	private static boolean startsWithBom(byte[] bytes, int first, int second) {
		return bytes.length >= 2 && (bytes[0] & 0xFF) == first && (bytes[1] & 0xFF) == second;
	}

	/** The delimiter named by a leading {@code sep=X} line (the convention Excel reads), or 0. */
	static char sepLine(String text) {
		return (sepLineLength(text) > 0) ? text.charAt(4) : 0;
	}

	/** Length of the {@code sep=X} line including its line break, 0 when there is none. */
	static int sepLineLength(String text) {
		if (text.length() < 6 || !text.startsWith("sep=")) {
			return 0;
		}
		char delimiter = text.charAt(4);
		if (delimiter == '"' || delimiter == '\r' || delimiter == '\n' || Character.isLetterOrDigit(delimiter)) {
			return 0;
		}
		int i = 5;
		if (text.charAt(i) == '\r') {
			i++;
		}
		if (i < text.length() && text.charAt(i) == '\n') {
			i++;
		}
		return (i > 5) ? i : 0;
	}

	/**
	 * The delimiter that cuts the first lines into the same number of cells (more than one) outside quotes; when no
	 * candidate is consistent, the one with the most cells on the first line; a comma when none splits at all.
	 */
	static char detectDelimiter(String text) {
		java.util.List<int[]> perLine = new java.util.ArrayList<>();
		int[] current = new int[CANDIDATES.length];
		boolean quoted = false;
		boolean any = false;
		int lines = 0;
		int limit = Math.min(text.length(), 16_384);
		for (int i = 0; i < limit && lines < 10; i++) {
			char c = text.charAt(i);
			if (c == '"') {
				quoted = !quoted;
				any = true;
			}
			else if (!quoted && (c == '\n' || c == '\r')) {
				if (c == '\r' && i + 1 < limit && text.charAt(i + 1) == '\n') {
					i++;
				}
				if (any) {
					perLine.add(current);
					lines++;
				}
				current = new int[CANDIDATES.length];
				any = false;
			}
			else if (!quoted) {
				any = true;
				for (int k = 0; k < CANDIDATES.length; k++) {
					if (c == CANDIDATES[k]) {
						current[k]++;
					}
				}
			}
			else {
				any = true;
			}
		}
		if (any) {
			perLine.add(current);
		}
		char best = ',';
		long bestScore = 0;
		for (int k = 0; k < CANDIDATES.length; k++) {
			int first = perLine.isEmpty() ? 0 : perLine.get(0)[k];
			if (first == 0) {
				continue;
			}
			boolean consistent = true;
			long sum = 0;
			for (int[] line : perLine) {
				consistent &= line[k] == first;
				sum += line[k];
			}
			long score = (consistent ? 1_000_000L : 0) + first * 100L + sum;
			if (score > bestScore) {
				bestScore = score;
				best = CANDIDATES[k];
			}
		}
		return best;
	}

}
