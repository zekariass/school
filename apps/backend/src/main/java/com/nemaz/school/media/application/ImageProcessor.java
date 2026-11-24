package com.nemaz.school.media.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.nemaz.school.media.MediaVariant;

/**
 * Re-encodes an uploaded image as JPEG renditions (THUMB 256, SMALL 640, MEDIUM 1280 px on the
 * longest side, never enlarged).
 *
 * <p>
 * <b>Privacy:</b> renditions are written from decoded pixels, so no metadata of the original
 * survives (EXIF with GPS position, camera and owner details, XMP, comments, embedded
 * thumbnails). The EXIF orientation is applied first, so sideways photos are shown upright.
 * Transparent images are flattened onto white. Large images are decoded subsampled, which
 * bounds memory; the dimension limit rejects decompression bombs before any pixel is read.
 *
 * <p>
 * JPEG rather than WebP: no pure-Java WebP writer exists (the TwelveMonkeys WebP plugin reads
 * only), and native libwebp bindings are not worth a platform dependency yet.
 */
final class ImageProcessor {

	static final int MAX_SIDE = 20_000;

	static final long MAX_PIXELS = 64_000_000L;

	private static final float QUALITY = 0.82f;

	private static final Set<String> FORMATS = Set.of("jpeg", "png", "webp");

	private static final List<Size> SIZES = List.of(new Size(MediaVariant.THUMB, 256),
			new Size(MediaVariant.SMALL, 640), new Size(MediaVariant.MEDIUM, 1280));

	private record Size(MediaVariant variant, int maxSide) {
	}

	/** One generated JPEG file. */
	record Rendition(MediaVariant variant, byte[] bytes, int width, int height) {
	}

	/** The upright size of the original and its renditions. */
	record Result(int width, int height, List<Rendition> renditions) {
	}

	private ImageProcessor() {
	}

	static Result process(byte[] original) throws UnprocessableMediaException {
		try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new UnprocessableMediaException("unreadable_image");
			}
			ImageReader reader = readers.next();
			try {
				if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
					throw new UnprocessableMediaException("unsupported_image_format");
				}
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
						|| (long) width * height > MAX_PIXELS) {
					throw new UnprocessableMediaException("image_too_large");
				}
				// Decode at most 2x the largest rendition: bounded memory whatever the original's size.
				int largest = SIZES.get(SIZES.size() - 1).maxSide();
				int subsampling = Math.max(1, Math.max(width, height) / largest);
				ImageReadParam param = reader.getDefaultReadParam();
				param.setSourceSubsampling(subsampling, subsampling, 0, 0);
				BufferedImage decoded = reader.read(0, param);
				boolean jpeg = "jpeg".equalsIgnoreCase(reader.getFormatName());
				int orientation = jpeg ? ExifOrientation.read(original) : 1;
				BufferedImage upright = flatten(decoded, orientation);
				boolean swapped = orientation >= 5;
				int uprightWidth = swapped ? height : width;
				int uprightHeight = swapped ? width : height;
				List<Rendition> renditions = new ArrayList<>();
				for (Size size : SIZES) {
					renditions.add(render(upright, size));
				}
				return new Result(uprightWidth, uprightHeight, renditions);
			}
			finally {
				reader.dispose();
			}
		}
		catch (UnprocessableMediaException ex) {
			throw ex;
		}
		catch (IOException | RuntimeException | OutOfMemoryError ex) {
			throw new UnprocessableMediaException("unreadable_image", ex);
		}
	}

	/** Draws onto an opaque sRGB image (white behind transparency), applying the EXIF orientation. */
	private static BufferedImage flatten(BufferedImage source, int orientation) {
		int w = source.getWidth();
		int h = source.getHeight();
		boolean swapped = orientation >= 5;
		BufferedImage out = new BufferedImage(swapped ? h : w, swapped ? w : h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, out.getWidth(), out.getHeight());
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(source, orientationTransform(orientation, w, h), null);
		}
		finally {
			g.dispose();
		}
		return out;
	}

	private static AffineTransform orientationTransform(int orientation, int width, int height) {
		AffineTransform t = new AffineTransform();
		switch (orientation) {
			case 2 -> {
				t.scale(-1.0, 1.0);
				t.translate(-width, 0);
			}
			case 3 -> {
				t.translate(width, height);
				t.rotate(Math.PI);
			}
			case 4 -> {
				t.scale(1.0, -1.0);
				t.translate(0, -height);
			}
			case 5 -> {
				t.rotate(-Math.PI / 2);
				t.scale(-1.0, 1.0);
			}
			case 6 -> {
				t.translate(height, 0);
				t.rotate(Math.PI / 2);
			}
			case 7 -> {
				t.scale(-1.0, 1.0);
				t.translate(-height, 0);
				t.translate(0, width);
				t.rotate(3 * Math.PI / 2);
			}
			case 8 -> {
				t.translate(0, width);
				t.rotate(3 * Math.PI / 2);
			}
			default -> {
			}
		}
		return t;
	}

	private static Rendition render(BufferedImage upright, Size size) throws IOException {
		int w = upright.getWidth();
		int h = upright.getHeight();
		int longest = Math.max(w, h);
		BufferedImage scaled = upright;
		if (longest > size.maxSide()) {
			double factor = (double) size.maxSide() / longest;
			scaled = scale(upright, Math.max(1, (int) Math.round(w * factor)), Math.max(1, (int) Math.round(h * factor)));
		}
		return new Rendition(size.variant(), encodeJpeg(scaled), scaled.getWidth(), scaled.getHeight());
	}

	/** Halves repeatedly (bilinear), then a final bicubic step: much sharper than one big jump. */
	static BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
		BufferedImage current = source;
		int w = source.getWidth();
		int h = source.getHeight();
		while (w != targetWidth || h != targetHeight) {
			w = (w > targetWidth) ? Math.max(targetWidth, w / 2) : targetWidth;
			h = (h > targetHeight) ? Math.max(targetHeight, h / 2) : targetHeight;
			boolean last = w == targetWidth && h == targetHeight;
			BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = next.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
						last ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(current, 0, 0, w, h, null);
			}
			finally {
				g.dispose();
			}
			current = next;
		}
		return current;
	}

	static byte[] encodeJpeg(BufferedImage image) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
				writer.setOutput(out);
				// No metadata object: the writer emits only the JFIF header, nothing of the original.
				writer.write(null, new IIOImage(image, null, null), param);
			}
			return bytes.toByteArray();
		}
		finally {
			writer.dispose();
		}
	}

}
