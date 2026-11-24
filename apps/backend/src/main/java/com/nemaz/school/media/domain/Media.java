package com.nemaz.school.media.domain;

import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.BaseEntity;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * An uploaded file and its lifecycle (Database_Design §4.9). The object key is derived from
 * the purpose and context when the upload is requested and never changes.
 */
@Entity
@Table(name = "media")
public class Media extends BaseEntity {

	private UUID ownerId;

	private UUID schoolId;

	@Enumerated(EnumType.STRING)
	private MediaPurpose purpose;

	@Enumerated(EnumType.STRING)
	private ContextType contextType;

	private UUID contextId;

	private String storageProvider;

	private String bucket;

	private String objectKey;

	private String originalFilename;

	private String mimeType;

	@Enumerated(EnumType.STRING)
	private MediaKind mediaKind;

	private Long sizeBytes;

	private String checksumSha256;

	private Integer width;

	private Integer height;

	private Integer durationMs;

	private Integer pageCount;

	@Enumerated(EnumType.STRING)
	private MediaStatus status;

	private String failureReason;

	private Instant uploadedAt;

	private Instant deletedAt;

	protected Media() {
	}

	/** A new upload in PENDING_UPLOAD; the id is assigned when the entity is persisted. */
	public static Media pending(UUID ownerId, MediaPurpose purpose, MediaContext context, UUID schoolId, String bucket,
			String objectKey, String originalFilename, String mimeType, MediaKind kind, long sizeBytes,
			String checksumSha256) {
		Media m = new Media();
		m.ownerId = ownerId;
		m.purpose = purpose;
		m.contextType = context.type();
		m.contextId = context.id();
		m.schoolId = schoolId;
		m.storageProvider = "S3";
		m.bucket = bucket;
		m.objectKey = objectKey;
		m.originalFilename = originalFilename;
		m.mimeType = mimeType;
		m.mediaKind = kind;
		m.sizeBytes = sizeBytes;
		m.checksumSha256 = checksumSha256;
		m.status = MediaStatus.PENDING_UPLOAD;
		return m;
	}

	/** Sets the final object key once the id is known (it embeds the id). */
	public void relocate(String objectKey) {
		this.objectKey = objectKey;
	}

	public void uploaded(Instant at) {
		this.status = MediaStatus.UPLOADED;
		this.uploadedAt = at;
	}

	public void processing() {
		this.status = MediaStatus.PROCESSING;
	}

	public void ready(Integer width, Integer height, Integer pageCount, Integer durationMs) {
		this.status = MediaStatus.READY;
		this.width = width;
		this.height = height;
		this.pageCount = pageCount;
		this.durationMs = durationMs;
		this.failureReason = null;
	}

	public void failed(String reason) {
		this.status = MediaStatus.FAILED;
		this.failureReason = reason;
	}

	public void quarantined(String reason) {
		this.status = MediaStatus.QUARANTINED;
		this.failureReason = reason;
	}

	public void deleted(Instant at) {
		this.status = MediaStatus.DELETED;
		this.deletedAt = at;
	}

	public MediaContext context() {
		return new MediaContext(this.contextType, this.contextId);
	}

	public MediaInfo info() {
		return new MediaInfo(getId(), this.ownerId, this.purpose, context(), this.schoolId, this.status,
				this.mediaKind, this.mimeType);
	}

	public UUID getOwnerId() {
		return this.ownerId;
	}

	public UUID getSchoolId() {
		return this.schoolId;
	}

	public MediaPurpose getPurpose() {
		return this.purpose;
	}

	public String getBucket() {
		return this.bucket;
	}

	public String getObjectKey() {
		return this.objectKey;
	}

	public String getOriginalFilename() {
		return this.originalFilename;
	}

	public String getMimeType() {
		return this.mimeType;
	}

	public MediaKind getMediaKind() {
		return this.mediaKind;
	}

	public Long getSizeBytes() {
		return this.sizeBytes;
	}

	public String getChecksumSha256() {
		return this.checksumSha256;
	}

	public Integer getWidth() {
		return this.width;
	}

	public Integer getHeight() {
		return this.height;
	}

	public Integer getDurationMs() {
		return this.durationMs;
	}

	public Integer getPageCount() {
		return this.pageCount;
	}

	public MediaStatus getStatus() {
		return this.status;
	}

	public String getFailureReason() {
		return this.failureReason;
	}

	public Instant getUploadedAt() {
		return this.uploadedAt;
	}

	public Instant getDeletedAt() {
		return this.deletedAt;
	}

}
