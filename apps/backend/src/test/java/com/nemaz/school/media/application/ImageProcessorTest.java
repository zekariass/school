package com.nemaz.school.media.application;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;

import com.nemaz.school.media.MediaVariant;
import com.nemaz.school.media.application.ImageProcessor.Rendition;
import com.nemaz.school.media.application.ImageProcessor.Result;
import com.nemaz.school.support.MediaFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessorTest {

	@Test
	void makesThreeJpegRenditionsWithinTheirLimitsAndKeepsTheAspectRatio() throws Exception {
		Result result = ImageProcessor.process(MediaFixtures.jpeg(3000, 2000));

		assertThat(result.width()).isEqualTo(3000);
		assertThat(result.height()).isEqualTo(2000);
		assertThat(result.renditions()).extracting(Rendition::variant)
			.containsExactly(MediaVariant.THUMB, MediaVariant.SMALL, MediaVariant.MEDIUM);
		Rendition thumb = rendition(result, MediaVariant.THUMB);
		assertThat(thumb.width()).isEqualTo(256);
		assertThat(thumb.height()).isEqualTo(171);
		assertThat(rendition(result, MediaVariant.SMALL).width()).isEqualTo(640);
		assertThat(rendition(result, MediaVariant.MEDIUM).width()).isEqualTo(1280);
		for (Rendition r : result.renditions()) {
			BufferedImage decoded = MediaFixtures.decode(r.bytes());
			assertThat(decoded.getWidth()).isEqualTo(r.width());
			assertThat(decoded.getHeight()).isEqualTo(r.height());
			assertThat(r.bytes()[0] & 0xFF).isEqualTo(0xFF); // JPEG
		}
	}

	@Test
	void neverEnlargesASmallImage() throws Exception {
		Result result = ImageProcessor.process(MediaFixtures.png(100, 60, false));

		assertThat(result.renditions()).allSatisfy((r) -> {
			assertThat(r.width()).isEqualTo(100);
			assertThat(r.height()).isEqualTo(60);
		});
	}

	@Test
	void stripsExifIncludingGpsFromEveryRendition() throws Exception {
		byte[] original = MediaFixtures.jpegWithGps(800, 600, 1);
		assertThat(MediaFixtures.hasGps(original)).as("fixture has GPS").isTrue();
		assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("SecretCamera");

		Result result = ImageProcessor.process(original);

		for (Rendition r : result.renditions()) {
			assertThat(MediaFixtures.hasExif(r.bytes())).as(r.variant() + " has no EXIF").isFalse();
			assertThat(MediaFixtures.hasGps(r.bytes())).as(r.variant() + " has no GPS").isFalse();
			String raw = new String(r.bytes(), StandardCharsets.ISO_8859_1);
			assertThat(raw).doesNotContain("Exif").doesNotContain("SecretCamera").doesNotContain("GPS");
		}
	}

	@Test
	void appliesTheExifOrientationBeforeDroppingIt() throws Exception {
		// 400x200, left half red, right half blue; orientation 6 means "rotate 90 degrees clockwise to display"
		Result result = ImageProcessor.process(MediaFixtures.jpegWithGps(400, 200, 6));

		assertThat(result.width()).isEqualTo(200);
		assertThat(result.height()).isEqualTo(400);
		BufferedImage medium = MediaFixtures.decode(rendition(result, MediaVariant.MEDIUM).bytes());
		assertThat(medium.getWidth()).isEqualTo(200);
		assertThat(medium.getHeight()).isEqualTo(400);
		// after a clockwise turn the red left half is the top half
		assertThat(isReddish(new Color(medium.getRGB(100, 50)))).isTrue();
		assertThat(isBluish(new Color(medium.getRGB(100, 350)))).isTrue();
	}

	@Test
	void flattensTransparencyOntoWhite() throws Exception {
		Result result = ImageProcessor.process(MediaFixtures.png(64, 64, true));

		BufferedImage thumb = MediaFixtures.decode(rendition(result, MediaVariant.THUMB).bytes());
		// the fixture paints both halves opaque; make sure colours survive (no black from dropped alpha)
		assertThat(isReddish(new Color(thumb.getRGB(10, 32)))).isTrue();
	}

	@Test
	void rejectsContentThatIsNotAnImage() {
		assertThatThrownBy(() -> ImageProcessor.process("definitely not an image".getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(UnprocessableMediaException.class)
			.hasMessage("unreadable_image");
		byte[] truncated = MediaFixtures.jpeg(300, 300);
		byte[] cut = java.util.Arrays.copyOf(truncated, 40);
		assertThatThrownBy(() -> ImageProcessor.process(cut)).isInstanceOf(UnprocessableMediaException.class);
	}

	@Test
	void rejectsAnImageWithAHugeDeclaredSizeBeforeDecodingIt() throws Exception {
		// A valid PNG header declaring 60000 x 60000 pixels, with no pixel data behind it.
		byte[] bomb = pngHeader(60_000, 60_000);

		assertThatThrownBy(() -> ImageProcessor.process(bomb)).isInstanceOf(UnprocessableMediaException.class)
			.hasMessage("image_too_large");
	}

	private static Rendition rendition(Result result, MediaVariant variant) {
		return result.renditions().stream().filter((r) -> r.variant() == variant).findFirst().orElseThrow();
	}

	private static boolean isReddish(Color c) {
		return c.getRed() > 180 && c.getBlue() < 90;
	}

	private static boolean isBluish(Color c) {
		return c.getBlue() > 180 && c.getRed() < 90;
	}

	/** Signature, IHDR chunk (with a wrong CRC, which the dimension check must not need) and IEND. */
	private static byte[] pngHeader(int width, int height) {
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 4 + 4 + 13 + 4);
		b.put(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
		b.putInt(13).put("IHDR".getBytes(StandardCharsets.US_ASCII));
		b.putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
		b.putInt(0);
		return b.array();
	}

}
