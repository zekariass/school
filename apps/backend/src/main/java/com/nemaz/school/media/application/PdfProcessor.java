package com.nemaz.school.media.application;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Counts the pages of a PDF and renders the first page as a JPEG preview (longest side at
 * most 1280 px). Nothing in the document is executed: PDFBox only parses and draws it.
 */
final class PdfProcessor {

	static final int PREVIEW_SIDE = 1280;

	static final int MAX_PAGES = 5_000;

	record Result(int pageCount, byte[] preview, int previewWidth, int previewHeight) {
	}

	private PdfProcessor() {
	}

	static Result process(byte[] pdf) throws UnprocessableMediaException {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			if (document.isEncrypted()) {
				throw new UnprocessableMediaException("encrypted_pdf");
			}
			int pages = document.getNumberOfPages();
			if (pages < 1 || pages > MAX_PAGES) {
				throw new UnprocessableMediaException("pdf_page_count");
			}
			PDPage first = document.getPage(0);
			PDRectangle box = first.getCropBox();
			float longest = Math.max(box.getWidth(), box.getHeight());
			if (!(longest > 0)) {
				throw new UnprocessableMediaException("pdf_page_size");
			}
			float scale = Math.min(3f, PREVIEW_SIDE / longest);
			BufferedImage page = new PDFRenderer(document).renderImage(0, scale, ImageType.RGB);
			return new Result(pages, ImageProcessor.encodeJpeg(page), page.getWidth(), page.getHeight());
		}
		catch (UnprocessableMediaException ex) {
			throw ex;
		}
		catch (IOException | RuntimeException | OutOfMemoryError ex) {
			throw new UnprocessableMediaException("unreadable_pdf", ex);
		}
	}

}
