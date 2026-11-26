package com.nemaz.school.media.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaDeleted;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.media.MediaStatus;
import com.nemaz.school.media.domain.Media;
import com.nemaz.school.media.domain.MediaVariantRow;
import com.nemaz.school.media.domain.ObjectStorage;
import com.nemaz.school.media.infrastructure.MediaRepository;
import com.nemaz.school.media.infrastructure.MediaVariantRepository;
import com.nemaz.school.media.infrastructure.StorageProperties;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and deleting media (Implementation Plan M2.2). Access follows R-30: the purpose's
 * policy, registered by the module that owns the object, decides. The uploader and the
 * managers of the context see media in any state; everyone else only READY media they may
 * read. Media the caller may not see is 404, as if it did not exist.
 *
 * <p>
 * Images are served only as the re-encoded variants (their metadata, such as GPS, is gone);
 * no signed link to an image original is ever issued.
 */
@Service
@Transactional(readOnly = true)
public class MediaReads {

	private final MediaRepository media;

	private final MediaVariantRepository variants;

	private final PurposePolicies policies;

	private final ObjectStorage storage;

	private final StorageProperties storageProperties;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	MediaReads(MediaRepository media, MediaVariantRepository variants, PurposePolicies policies, ObjectStorage storage,
			StorageProperties storageProperties, AuditLog audit, ApplicationEventPublisher events, Clock clock) {
		this.media = media;
		this.variants = variants;
		this.policies = policies;
		this.storage = storage;
		this.storageProperties = storageProperties;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public MediaDetails get(CurrentUser user, UUID mediaId) {
		Media m = visible(user, mediaId);
		return new MediaDetails(m, this.variants.findByMediaIdOrderByVariant(m.getId()));
	}

	/**
	 * A signed GET link for one rendition. Images need a variant ({@code THUMB}, {@code SMALL},
	 * {@code MEDIUM}); documents and videos default to the original. Not read-only: the purposes whose reads are
	 * audited (roster import files) write an audit row.
	 */
	@Transactional
	public SignedUrl url(CurrentUser user, UUID mediaId, RequestedVariant requested) {
		Media m = visible(user, mediaId);
		if (m.getStatus() != MediaStatus.READY) {
			throw ApiException.conflict("media.not_ready");
		}
		String key;
		String mimeType;
		String disposition;
		if (requested == null || requested == RequestedVariant.ORIGINAL) {
			if (m.getMediaKind() == MediaKind.IMAGE) {
				throw ApiException.badRequest((requested == null) ? "media.variant_required" : "media.original_not_available");
			}
			key = m.getObjectKey();
			mimeType = m.getMimeType();
			disposition = (m.getMediaKind() == MediaKind.VIDEO) ? "inline" : attachment(m.getOriginalFilename());
		}
		else {
			MediaVariantRow row = this.variants.findByMediaIdOrderByVariant(m.getId())
				.stream()
				.filter((v) -> v.getVariant() == requested.variant())
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("media.variant_not_found"));
			key = row.getObjectKey();
			mimeType = row.getMimeType();
			disposition = "inline";
		}
		MediaPurposePolicy policy = this.policies.find(m.getPurpose()).orElse(null);
		if (policy != null && policy.auditReads()) {
			this.audit.record(AuditEntry.of("media.read", AuditOutcome.SUCCESS)
				.target("media", m.getId())
				.school(m.getSchoolId())
				.metadata("purpose", m.getPurpose().name())
				.metadata("variant", (requested != null) ? requested.name() : "ORIGINAL"));
		}
		Instant expiresAt = this.clock.instant().plus(this.storageProperties.readUrlTtl());
		URI url = this.storage.presignGet(key, this.storageProperties.readUrlTtl(), disposition, mimeType);
		return new SignedUrl(url, expiresAt);
	}

	/** Soft-deletes the media; owning modules clear their references and its objects are removed. */
	@Transactional
	public void delete(CurrentUser user, UUID mediaId) {
		Media m = this.media.lockById(mediaId)
			.filter((x) -> x.getStatus() != MediaStatus.DELETED)
			.orElseThrow(() -> ApiException.notFound("media.not_found"));
		MediaInfo info = m.info();
		MediaPurposePolicy policy = this.policies.find(m.getPurpose()).orElse(null);
		boolean owner = m.getOwnerId().equals(user.userId());
		boolean manager = owner || (policy != null && policy.canManage(user, info));
		if (!manager) {
			if (policy == null || !policy.canRead(user, info)) {
				throw ApiException.notFound("media.not_found");
			}
			throw ApiException.forbidden("media.forbidden");
		}
		release(m, "media.delete");
	}

	/** Used by {@link DefaultMediaService#discard}: no permission check. */
	@Transactional
	void release(Media m, String auditAction) {
		m.deleted(this.clock.instant());
		this.media.saveAndFlush(m);
		this.audit.record(AuditEntry.of(auditAction, AuditOutcome.SUCCESS)
			.target("media", m.getId())
			.school(m.getSchoolId())
			.metadata("purpose", m.getPurpose().name()));
		this.events.publishEvent(new MediaDeleted(m.getId()));
	}

	/** The purposes users may upload, with their limits (only purposes whose module registered a policy). */
	public List<PurposeLimits> policies() {
		List<PurposeLimits> result = new java.util.ArrayList<>();
		for (MediaPurpose purpose : MediaPurpose.values()) {
			if (this.policies.supported().contains(purpose)) {
				PurposeRules.Rule rule = PurposeRules.of(purpose);
				result.add(new PurposeLimits(purpose, List.copyOf(rule.contexts()),
						new LinkedHashMap<>(rule.maxBytesByMimeType())));
			}
		}
		return result;
	}

	/**
	 * The media if the caller may see it, else 404. The uploader and managers see any state;
	 * others only READY media their purpose policy lets them read.
	 */
	private Media visible(CurrentUser user, UUID mediaId) {
		Media m = this.media.findById(mediaId)
			.filter((x) -> x.getStatus() != MediaStatus.DELETED)
			.orElseThrow(() -> ApiException.notFound("media.not_found"));
		MediaPurposePolicy policy = this.policies.find(m.getPurpose()).orElse(null);
		MediaInfo info = m.info();
		if (m.getOwnerId().equals(user.userId()) && (policy == null || policy.ownerAlwaysReads(info))) {
			return m;
		}
		boolean allowed = policy != null && (policy.canManage(user, info)
				|| (m.getStatus() == MediaStatus.READY && policy.canRead(user, info)));
		if (!allowed) {
			throw ApiException.notFound("media.not_found");
		}
		return m;
	}

	private static String attachment(String filename) {
		return ContentDisposition.attachment().filename((filename != null) ? filename : "file", java.nio.charset.StandardCharsets.UTF_8).build().toString();
	}

	public record SignedUrl(URI url, Instant expiresAt) {
	}

	public record PurposeLimits(MediaPurpose purpose, List<ContextType> contextTypes, Map<String, Long> maxBytesByMimeType) {
	}

}
