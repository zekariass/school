package com.nemaz.school.media.application;

import com.nemaz.school.support.MediaFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MagicBytesTest {

	@Test
	void recognisesTheAcceptedImageAndDocumentSignatures() {
		assertThat(MagicBytes.detect(MediaFixtures.jpeg(10, 10))).containsExactly("image/jpeg");
		assertThat(MagicBytes.detect(MediaFixtures.png(10, 10, false))).containsExactly("image/png");
		assertThat(MagicBytes.detect(MediaFixtures.pdf(1))).containsExactly("application/pdf");
		assertThat(MagicBytes.detect(webp())).containsExactly("image/webp");
		assertThat(MagicBytes.detect("GIF89a....".getBytes())).containsExactly("image/gif");
		assertThat(MagicBytes.detect(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0 })).containsExactly("video/webm");
	}

	@Test
	void tellsMp4FromQuickTime() {
		assertThat(MagicBytes.detect(MediaFixtures.mp4(64))).containsExactly("video/mp4");
		byte[] mov = MediaFixtures.mp4(64);
		System.arraycopy("qt  ".getBytes(), 0, mov, 8, 4);
		assertThat(MagicBytes.detect(mov)).containsExactly("video/quicktime");
	}

	@Test
	void anExecutableOrScriptIsNotAnAcceptedType() {
		assertThat(MagicBytes.detect(new byte[] { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0 })).isEmpty();
		assertThat(MagicBytes.detect(new byte[] { 0x7F, 'E', 'L', 'F', 2, 1, 1, 0 })).isEmpty();
		assertThat(MagicBytes.detect(new byte[0])).isEmpty();
	}

	@Test
	void plainTextMayBeDeclaredAsCsvPlainTextOrTheExcelType() {
		assertThat(MagicBytes.detect("name,grade\nAbebe,5\n".getBytes())).containsExactlyInAnyOrder("text/csv",
				"text/plain", "application/vnd.ms-excel");
		assertThat(MagicBytes.detect("<?php echo 1; \0".getBytes())).isEmpty();
	}

	@Test
	void utf16TextWithAByteOrderMarkIsText_butOtherNulHeavyBytesAreNot() {
		byte[] utf16 = ("\uFEFFname\tgrade\r\nAbebe\t5\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
		assertThat(utf16[0] & 0xFF).isEqualTo(0xFF);
		assertThat(MagicBytes.detect(utf16)).contains("text/csv");
		byte[] withoutBom = "name\tgrade\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
		assertThat(MagicBytes.detect(withoutBom)).isEmpty();
		assertThat(MagicBytes.detect(new byte[] { (byte) 0xFF, (byte) 0xFE, 1, 0, 2, 0, 0, 0 })).isEmpty();
	}

	@Test
	void anXlsxIsAZipAndTheOldBinaryWorkbookIsRefused() {
		assertThat(MagicBytes.detect(new byte[] { 'P', 'K', 3, 4, 0, 0 })).containsExactly(
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
		byte[] ole2 = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 };
		assertThat(MagicBytes.detect(ole2)).isEmpty();
	}

	@Test
	void aMismatchBetweenDeclaredTypeAndContentIsVisible() {
		assertThat(MagicBytes.detect(MediaFixtures.pdf(1))).doesNotContain("image/jpeg");
		assertThat(MagicBytes.detect(MediaFixtures.png(10, 10, false))).doesNotContain("image/jpeg");
	}

	private static byte[] webp() {
		return new byte[] { 'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ' };
	}

}
