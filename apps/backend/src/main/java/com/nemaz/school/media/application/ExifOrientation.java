package com.nemaz.school.media.application;

/**
 * Reads the EXIF orientation (1-8) of a JPEG, so photos taken sideways are turned upright
 * before their metadata is dropped. Returns 1 (as is) when there is none or the data is
 * malformed: a bad EXIF block must never fail processing.
 */
final class ExifOrientation {

	private static final int ORIENTATION_TAG = 0x0112;

	private ExifOrientation() {
	}

	static int read(byte[] jpeg) {
		try {
			return readOrThrow(jpeg);
		}
		catch (RuntimeException ex) {
			return 1;
		}
	}

	private static int readOrThrow(byte[] d) {
		if (d.length < 4 || u8(d, 0) != 0xFF || u8(d, 1) != 0xD8) {
			return 1;
		}
		int pos = 2;
		while (pos + 4 <= d.length) {
			if (u8(d, pos) != 0xFF) {
				return 1;
			}
			int marker = u8(d, pos + 1);
			if (marker == 0xFF) {
				pos++;
				continue;
			}
			if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				pos += 2;
				continue;
			}
			if (marker == 0xDA || marker == 0xD9) {
				return 1; // start of scan or end of image: no EXIF before the pixels
			}
			int length = (u8(d, pos + 2) << 8) | u8(d, pos + 3);
			if (length < 2 || pos + 2 + length > d.length) {
				return 1;
			}
			if (marker == 0xE1 && length >= 2 + 6 + 8 && d[pos + 4] == 'E' && d[pos + 5] == 'x' && d[pos + 6] == 'i'
					&& d[pos + 7] == 'f' && d[pos + 8] == 0 && d[pos + 9] == 0) {
				return fromTiff(d, pos + 10, pos + 2 + length);
			}
			pos += 2 + length;
		}
		return 1;
	}

	private static int fromTiff(byte[] d, int start, int end) {
		boolean little;
		if (d[start] == 'I' && d[start + 1] == 'I') {
			little = true;
		}
		else if (d[start] == 'M' && d[start + 1] == 'M') {
			little = false;
		}
		else {
			return 1;
		}
		if (u16(d, start + 2, little) != 42) {
			return 1;
		}
		long ifd = start + (u32(d, start + 4, little));
		if (ifd < start || ifd + 2 > end) {
			return 1;
		}
		int entries = u16(d, (int) ifd, little);
		for (int i = 0; i < entries; i++) {
			int entry = (int) ifd + 2 + i * 12;
			if (entry + 12 > end) {
				return 1;
			}
			if (u16(d, entry, little) == ORIENTATION_TAG && u16(d, entry + 2, little) == 3) {
				int value = u16(d, entry + 8, little);
				return (value >= 1 && value <= 8) ? value : 1;
			}
		}
		return 1;
	}

	private static int u8(byte[] d, int i) {
		return d[i] & 0xFF;
	}

	private static int u16(byte[] d, int i, boolean little) {
		return little ? (u8(d, i) | (u8(d, i + 1) << 8)) : ((u8(d, i) << 8) | u8(d, i + 1));
	}

	private static long u32(byte[] d, int i, boolean little) {
		long b0 = u8(d, i);
		long b1 = u8(d, i + 1);
		long b2 = u8(d, i + 2);
		long b3 = u8(d, i + 3);
		return little ? (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)) : ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3);
	}

}
