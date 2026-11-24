package com.nemaz.school.media.application;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaStatus;
import com.nemaz.school.media.MediaVariant;
import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.MediaVariantRow;
import com.nemaz.school.media.domain.ObjectStorage;
import com.nemaz.school.media.infrastructure.MediaRepository;
import com.nemaz.school.media.infrastructure.MediaVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes uploaded media (Implementation Plan M2.1): checks the checksum, makes image
 * renditions (metadata stripped) or the PDF preview, and marks the media READY. Videos are not
 * transcoded yet (the ffmpeg worker arrives with stories, M8): they are READY as uploaded.
 *
 * <p>
 * Runs outside a transaction while it works (images take a while), with short transactions
 * around the state changes. Idempotent: the event is delivered at least once, so it accepts
 * media that is UPLOADED or still PROCESSING (a crashed earlier attempt), ignores everything
 * else, and rewrites variants with fixed keys. A permanent problem with the content ends in
 * FAILED; a storage error propagates so the outbox redelivers the event.
 */
@Component
class MediaProcessor {

	private static final Logger log = LoggerFactory.getLogger(MediaProcessor.class);

	private static final String JPEG = MimeCatalog.JPEG;

	private final MediaRepository media;

	private final MediaVariantRepository variants;

	private final ObjectStorage storage;

	private final AuditLog audit;

	private final TransactionTemplate tx;

	MediaProcessor(MediaRepository media, MediaVariantRepository variants, ObjectStorage storage, AuditLog audit,
			PlatformTransactionManager transactions) {
		this.media = media;
		this.variants = variants;
		this.storage = storage;
		this.audit = audit;
		this.tx = new TransactionTemplate(transactions);
	}

	void process(UUID mediaId) {
		Work work = this.tx.execute((status) -> claim(mediaId));
		if (work == null) {
			return;
		}
		Outcome outcome;
		try {
			outcome = run(work);
		}
		catch (UnprocessableMediaException ex) {
			log.warn("Media {} could not be processed: {}", mediaId, ex.reason());
			this.tx.executeWithoutResult((status) -> fail(mediaId, ex.reason()));
			return;
		}
		this.tx.executeWithoutResult((status) -> finish(mediaId, outcome));
	}

	/** UPLOADED or PROCESSING becomes PROCESSING; anything else is nothing to do. */
	private Work claim(UUID mediaId) {
		Media m = this.media.lockById(mediaId).orElse(null);
		if (m == null || (m.getStatus() != MediaStatus.UPLOADED && m.getStatus() != MediaStatus.PROCESSING)) {
			return null;
		}
		m.processing();
		this.media.saveAndFlush(m);
		return new Work(m.getId(), m.getObjectKey(), m.getMimeType(), m.getMediaKind(), m.getSizeBytes(),
				m.getChecksumSha256());
	}

	private Outcome run(Work work) throws UnprocessableMediaException {
		boolean image = work.kind() == MediaKind.IMAGE;
		boolean pdf = MimeCatalog.PDF.equals(work.mimeType());
		if (!image && !pdf) {
			return new Outcome(null, null, null, null, List.of());
		}
		byte[] original = download(work);
		verifyChecksum(work, original);
		if (image) {
			ImageProcessor.Result result = ImageProcessor.process(original);
			List<Stored> stored = new ArrayList<>();
			for (ImageProcessor.Rendition r : result.renditions()) {
				stored.add(store(work, r.variant(), r.bytes(), r.width(), r.height()));
			}
			return new Outcome(result.width(), result.height(), null, null, stored);
		}
		PdfProcessor.Result result = PdfProcessor.process(original);
		Stored preview = store(work, MediaVariant.PDF_PREVIEW, result.preview(), result.previewWidth(),
				result.previewHeight());
		return new Outcome(null, null, result.pageCount(), null, List.of(preview));
	}

	private byte[] download(Work work) throws UnprocessableMediaException {
		try (InputStream in = this.storage.get(work.objectKey())) {
			byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, work.sizeBytes() + 1));
			if (bytes.length != work.sizeBytes()) {
				throw new UnprocessableMediaException("size_changed");
			}
			return bytes;
		}
		catch (IOException ex) {
			throw new ObjectStorage.StorageException("Could not read the uploaded object", ex);
		}
	}

	private static void verifyChecksum(Work work, byte[] content) throws UnprocessableMediaException {
		if (work.sha256() == null) {
			return;
		}
		try {
			String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
			if (!actual.equals(work.sha256())) {
				throw new UnprocessableMediaException("checksum_mismatch");
			}
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Stored store(Work work, MediaVariant variant, byte[] bytes, int width, int height) {
		String key = variantKey(work.objectKey(), variant);
		this.storage.put(key, bytes, JPEG);
		return new Stored(variant, key, JPEG, width, height, bytes.length);
	}

	/** {@code .../{mediaId}/original} becomes {@code .../{mediaId}/thumb}. */
	static String variantKey(String originalKey, MediaVariant variant) {
		int slash = originalKey.lastIndexOf('/');
		return originalKey.substring(0, slash + 1) + variant.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}

	private void finish(UUID mediaId, Outcome outcome) {
		Media m = this.media.lockById(mediaId).orElse(null);
		if (m == null || m.getStatus() != MediaStatus.PROCESSING) {
			// Deleted while processing: its objects are being removed, so drop the new ones too.
			outcome.variants().forEach((v) -> this.storage.delete(v.key()));
			return;
		}
		this.variants.deleteByMediaId(mediaId);
		this.variants.flush();
		outcome.variants()
			.forEach((v) -> this.variants.save(
					new MediaVariantRow(mediaId, v.variant(), v.key(), v.mimeType(), v.width(), v.height(), v.size())));
		m.ready(outcome.width(), outcome.height(), outcome.pageCount(), outcome.durationMs());
		this.media.saveAndFlush(m);
	}

	private void fail(UUID mediaId, String reason) {
		Media m = this.media.lockById(mediaId).orElse(null);
		if (m == null || m.getStatus() != MediaStatus.PROCESSING) {
			return;
		}
		m.failed(reason);
		this.media.saveAndFlush(m);
		this.audit.record(AuditEntry.of("media.process", AuditOutcome.FAILURE)
			.actor(null)
			.target("media", mediaId)
			.school(m.getSchoolId())
			.metadata("purpose", m.getPurpose().name())
			.metadata("reason", reason));
	}

	private record Work(UUID id, String objectKey, String mimeType, MediaKind kind, long sizeBytes, String sha256) {
	}

	private record Stored(MediaVariant variant, String key, String mimeType, int width, int height, long size) {
	}

	private record Outcome(Integer width, Integer height, Integer pageCount, Integer durationMs, List<Stored> variants) {
	}

}
