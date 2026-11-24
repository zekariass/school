package com.nemaz.school.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Generated files for the media tests: no binary fixtures are checked in. */
public final class MediaFixtures {

	private MediaFixtures() {
	}

	/** A plain JPEG: left half red, right half blue (so orientation is observable). */
	public static byte[] jpeg(int width, int height) {
		return encode(halves(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
	}

	/**
	 * A JPEG carrying EXIF with a GPS position (Addis Ababa), the camera make and an orientation
	 * (1 = upright, 6 = rotate 90 degrees clockwise to display).
	 */
	public static byte[] jpegWithGps(int width, int height, int orientation) {
		try {
			byte[] plain = jpeg(width, height);
			TiffOutputSet exif = new TiffOutputSet();
			exif.setGpsInDegrees(38.7578, 8.9806);
			TiffOutputDirectory root = exif.getOrCreateRootDirectory();
			root.add(TiffTagConstants.TIFF_TAG_MAKE, "SecretCamera Inc");
			root.add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) orientation);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			new ExifRewriter().updateExifMetadataLossless(plain, out, exif);
			return out.toByteArray();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static byte[] png(int width, int height, boolean transparent) {
		BufferedImage image = halves(width, height, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		return encode(image, "png");
	}

	/** A PDF with {@code pages} pages of text. */
	public static byte[] pdf(int pages) {
		try (PDDocument document = new PDDocument()) {
			for (int i = 1; i <= pages; i++) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
					content.newLineAtOffset(72, 700);
					content.showText("Page " + i);
					content.endText();
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** The first bytes of an MP4 file (ftyp box); enough for the sniffer, not a playable video. */
	public static byte[] mp4(int size) {
		byte[] data = new byte[size];
		byte[] header = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '1' };
		System.arraycopy(header, 0, data, 0, header.length);
		return data;
	}

	public static byte[] text(String content) {
		return content.getBytes(StandardCharsets.UTF_8);
	}

	/** True when the JPEG carries an EXIF block with GPS data. */
	public static boolean hasGps(byte[] jpeg) {
		try {
			ImageMetadata metadata = Imaging.getMetadata(jpeg);
			return metadata instanceof JpegImageMetadata jpegMetadata && jpegMetadata.getExif() != null
					&& jpegMetadata.getExif().getGpsInfo() != null;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** True when the JPEG has any EXIF block at all. */
	public static boolean hasExif(byte[] jpeg) {
		try {
			return Imaging.getMetadata(jpeg) instanceof JpegImageMetadata m && m.getExif() != null;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static BufferedImage decode(byte[] image) {
		try {
			return ImageIO.read(new java.io.ByteArrayInputStream(image));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static BufferedImage halves(int width, int height, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		if (type == BufferedImage.TYPE_INT_ARGB) {
			g.setComposite(java.awt.AlphaComposite.Clear);
			g.fillRect(0, 0, width, height);
			g.setComposite(java.awt.AlphaComposite.SrcOver);
		}
		g.setColor(Color.RED);
		g.fillRect(0, 0, width / 2, height);
		g.setColor(Color.BLUE);
		g.fillRect(width / 2, 0, width - width / 2, height);
		g.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, format, out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
