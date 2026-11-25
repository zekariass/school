package com.nemaz.school.media.application;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.media.MediaStatus;
import com.nemaz.school.media.MediaUploaded;
import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.ObjectStorage;
import com.nemaz.school.media.domain.ObjectStorage.ObjectHead;
import com.nemaz.school.media.domain.ObjectStorage.PresignedUpload;
import com.nemaz.school.media.infrastructure.MediaRepository;
import com.nemaz.school.media.infrastructure.MediaVariantRepository;
import com.nemaz.school.media.infrastructure.StorageProperties;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * The first two steps of an upload (Implementation Plan M2.1): a presigned {@code PUT} for the
 * client, and the completion check once the client has sent the bytes. The checks that matter
 * for safety run in {@link #complete}: the object's size and content type must equal what was
 * declared, and its first bytes must have the signature of that type. Anything else is
 * quarantined (the object is deleted).
 */
@Service
public class MediaUploads {

	/** Upload URLs per user and hour. */
	static final int UPLOADS_PER_HOUR = 120;

	private static final int MAX_FILENAME = 200;

	private final MediaRepository media;

	private final MediaVariantRepository variants;

	private final PurposePolicies policies;

	private final ObjectStorage storage;

	private final StorageProperties storageProperties;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	MediaUploads(MediaRepository media, MediaVariantRepository variants, PurposePolicies policies,
			ObjectStorage storage, StorageProperties storageProperties, RateLimiter limiter, AuditLog audit,
			ApplicationEventPublisher events, Clock clock) {
		this.media = media;
		this.variants = variants;
		this.policies = policies;
		this.storage = storage;
		this.storageProperties = storageProperties;
		this.limiter = limiter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	/**
	 * Creates a PENDING_UPLOAD media and the presigned PUT for it. The purpose must have a
	 * policy, the caller must be allowed to create media in the context, and the file must
	 * satisfy the purpose's type and size rules.
	 */
	@Transactional
	public Ticket request(CurrentUser user, UploadRequest r) {
		MediaPurposePolicy policy = this.policies.require(r.purpose());
		PurposeRules.Rule rule = PurposeRules.of(r.purpose());
		MediaContext context = resolveContext(user, r.purpose(), r.context());
		if (!rule.contexts().contains(context.type())) {
			throw ApiException.badRequest("media.context_invalid");
		}
		if (!policy.canCreate(user, context)) {
			throw ApiException.forbidden("media.forbidden");
		}
		this.limiter.consume("media-upload-user", user.userId().toString(), UPLOADS_PER_HOUR, Duration.ofHours(1),
				"media.rate_limited");

		String mimeType = MimeCatalog.normalize(r.mimeType());
		if (!rule.allows(mimeType)) {
			throw ApiException.badRequest("media.type_not_allowed");
		}
		if (!PurposeRules.acceptsFilename(r.purpose(), mimeType, r.filename())) {
			throw ApiException.badRequest("media.type_not_allowed");
		}
		if (r.sizeBytes() < 1) {
			throw ApiException.badRequest("media.empty");
		}
		long max = rule.maxBytes(mimeType);
		if (r.sizeBytes() > max) {
			throw ApiException.badRequest("media.too_large", max / PurposeRules.MB);
		}
		String sha256 = null;
		if (r.sha256() != null && !r.sha256().isBlank()) {
			sha256 = r.sha256().toLowerCase(Locale.ROOT);
			if (!sha256.matches("[0-9a-f]{64}")) {
				throw ApiException.badRequest("media.checksum_invalid");
			}
		}
		UUID schoolId = (context.type() == ContextType.SCHOOL) ? context.id() : null;
		MediaKind kind = MimeCatalog.kind(mimeType);
		Media row = this.media.saveAndFlush(Media.pending(user.userId(), r.purpose(), context, schoolId,
				this.storageProperties.bucket(), "pending/" + Ids.newId(), sanitizeFilename(r.filename()), mimeType, kind,
				r.sizeBytes(), sha256));
		row.relocate(objectKey(context, r.purpose(), row.getId()));
		this.media.saveAndFlush(row);

		PresignedUpload upload = this.storage.presignPut(row.getObjectKey(), mimeType, r.sizeBytes(),
				this.storageProperties.uploadUrlTtl());
		return new Ticket(row.getId(), upload);
	}

	/**
	 * Verifies the uploaded object and queues processing. Calling it again for media that is
	 * already past this step returns its current state. A failed check quarantines the media
	 * (the status change is kept although the call answers 400).
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public MediaDetails complete(CurrentUser user, UUID mediaId) {
		Media m = this.media.lockById(mediaId)
			.filter((x) -> x.getOwnerId().equals(user.userId()) && x.getStatus() != MediaStatus.DELETED)
			.orElseThrow(() -> ApiException.notFound("media.not_found"));
		switch (m.getStatus()) {
			case UPLOADED, PROCESSING, READY -> {
				return details(m);
			}
			case PENDING_UPLOAD -> {
			}
			default -> throw ApiException.conflict("media.invalid_state");
		}
		ObjectHead head = this.storage.head(m.getObjectKey())
			.orElseThrow(() -> ApiException.conflict("media.not_uploaded"));
		if (head.size() != m.getSizeBytes()) {
			throw quarantine(m, "size_mismatch", "media.size_mismatch", "declared", m.getSizeBytes(), "actual",
					head.size());
		}
		if (!MimeCatalog.normalize(head.contentType()).equals(m.getMimeType())) {
			throw quarantine(m, "content_type_mismatch", "media.type_mismatch", "declared", m.getMimeType(), "actual",
					MimeCatalog.normalize(head.contentType()));
		}
		Set<String> detected = MagicBytes.detect(this.storage.getRange(m.getObjectKey(), 0, MagicBytes.SNIFF_LENGTH));
		if (!detected.contains(m.getMimeType())) {
			throw quarantine(m, "content_mismatch", "media.type_mismatch", "declared", m.getMimeType(), "detected",
					String.join(",", detected));
		}
		m.uploaded(this.clock.instant());
		this.media.saveAndFlush(m);
		this.events.publishEvent(new MediaUploaded(m.getId()));
		return details(m);
	}

	private ApiException quarantine(Media m, String reason, String code, String detailKey, Object detail,
			String otherKey, Object other) {
		m.quarantined(reason);
		this.media.saveAndFlush(m);
		this.storage.delete(m.getObjectKey());
		this.audit.record(AuditEntry.of("media.quarantine", AuditOutcome.FAILURE)
			.target("media", m.getId())
			.school(m.getSchoolId())
			.metadata("purpose", m.getPurpose().name())
			.metadata("reason", reason)
			.metadata(detailKey, detail)
			.metadata(otherKey, other));
		return ApiException.badRequest(code);
	}

	private MediaDetails details(Media m) {
		return new MediaDetails(m, this.variants.findByMediaIdOrderByVariant(m.getId()));
	}

	/** Where the media belongs: the given context, or for avatars the caller. */
	private static MediaContext resolveContext(CurrentUser user, MediaPurpose purpose, MediaContext requested) {
		if (requested != null) {
			return requested;
		}
		if (purpose == MediaPurpose.AVATAR) {
			return MediaContext.user(user.userId());
		}
		throw ApiException.badRequest("media.context_required");
	}

	/** {@code users/{id}/avatar/{mediaId}/original}, {@code schools/{id}/branding/{mediaId}/original}. */
	static String objectKey(MediaContext context, MediaPurpose purpose, UUID mediaId) {
		return context.type().keySegment() + "/" + context.id() + "/" + purpose.keySegment() + "/" + mediaId
				+ "/original";
	}

	/** The file name is only a label (downloads): strip any path, control characters and quotes, and cap its length. */
	static String sanitizeFilename(String filename) {
		if (filename == null) {
			return "file";
		}
		String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
		StringBuilder clean = new StringBuilder();
		name.codePoints().filter((c) -> c >= 0x20 && c != 0x7F && c != '"' && c != ';').forEach(clean::appendCodePoint);
		String result = clean.toString().strip();
		if (result.length() > MAX_FILENAME) {
			result = result.substring(0, MAX_FILENAME);
		}
		return result.isEmpty() ? "file" : result;
	}

	/** {@code context} may be null for purposes that default it (avatars). */
	public record UploadRequest(MediaPurpose purpose, String filename, String mimeType, long sizeBytes, String sha256,
			MediaContext context) {
	}

	public record Ticket(UUID mediaId, PresignedUpload upload) {
	}

}
