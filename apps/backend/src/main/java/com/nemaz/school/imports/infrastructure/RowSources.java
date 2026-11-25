package com.nemaz.school.imports.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.domain.ImportLimits;
import com.nemaz.school.imports.domain.RowSource;

/**
 * Chooses the reader by the file's content, not its name: a ZIP is a workbook, the old binary workbook (OLE2) and
 * macro workbooks are refused, anything else is text. The media upload has already refused wrong types and sizes;
 * this is the second line.
 */
public final class RowSources {

	private RowSources() {
	}

	public static RowSource open(Path file, String filename, ImportLimits limits, SourceOptions options) {
		byte[] head = new byte[8];
		int read;
		try (InputStream in = Files.newInputStream(file)) {
			read = in.readNBytes(head, 0, head.length);
			if (Files.size(file) > limits.maxFileBytes()) {
				throw ApiException.badRequest("import.file_too_large", limits.maxFileBytes() / (1024 * 1024));
			}
		}
		catch (IOException ex) {
			throw ApiException.badRequest("import.unsupported_file");
		}
		String lower = (filename == null) ? "" : filename.toLowerCase(java.util.Locale.ROOT);
		if (lower.endsWith(".xls") || lower.endsWith(".xlsm") || lower.endsWith(".xlsb")) {
			throw ApiException.badRequest("import.unsupported_file");
		}
		if (read >= 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11
				&& (head[3] & 0xFF) == 0xE0) {
			throw ApiException.badRequest("import.unsupported_file");
		}
		if (read >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
			return new XlsxRowSource(file, limits, options);
		}
		return new CsvRowSource(file, limits, options);
	}

}
