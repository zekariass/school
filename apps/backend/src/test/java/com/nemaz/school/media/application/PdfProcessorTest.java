package com.nemaz.school.media.application;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;

import com.nemaz.school.support.MediaFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfProcessorTest {

	@Test
	void countsPagesAndRendersTheFirstAsAJpegPreview() throws Exception {
		PdfProcessor.Result result = PdfProcessor.process(MediaFixtures.pdf(3));

		assertThat(result.pageCount()).isEqualTo(3);
		BufferedImage preview = MediaFixtures.decode(result.preview());
		assertThat(preview.getWidth()).isEqualTo(result.previewWidth());
		assertThat(Math.max(preview.getWidth(), preview.getHeight())).isBetween(1279, 1281);
		// A4 portrait
		assertThat(preview.getHeight()).isGreaterThan(preview.getWidth());
		assertThat(result.preview()[0] & 0xFF).isEqualTo(0xFF);
	}

	@Test
	void rejectsABrokenPdf() {
		assertThatThrownBy(() -> PdfProcessor.process("%PDF-1.7 garbage".getBytes(StandardCharsets.US_ASCII)))
			.isInstanceOf(UnprocessableMediaException.class);
	}

}
