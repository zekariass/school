package com.nemaz.school.media.domain;

import java.util.UUID;

import com.nemaz.school.common.persistence.IdentifiedEntity;
import com.nemaz.school.media.MediaVariant;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** A stored rendition of a media object ({@code media_variants}). */
@Entity
@Table(name = "media_variants")
public class MediaVariantRow extends IdentifiedEntity {

	private UUID mediaId;

	@Enumerated(EnumType.STRING)
	private MediaVariant variant;

	private String objectKey;

	private String mimeType;

	private Integer width;

	private Integer height;

	private Long sizeBytes;

	protected MediaVariantRow() {
	}

	public MediaVariantRow(UUID mediaId, MediaVariant variant, String objectKey, String mimeType, Integer width,
			Integer height, long sizeBytes) {
		this.mediaId = mediaId;
		this.variant = variant;
		this.objectKey = objectKey;
		this.mimeType = mimeType;
		this.width = width;
		this.height = height;
		this.sizeBytes = sizeBytes;
	}

	public UUID getMediaId() {
		return this.mediaId;
	}

	public MediaVariant getVariant() {
		return this.variant;
	}

	public String getObjectKey() {
		return this.objectKey;
	}

	public String getMimeType() {
		return this.mimeType;
	}

	public Integer getWidth() {
		return this.width;
	}

	public Integer getHeight() {
		return this.height;
	}

	public Long getSizeBytes() {
		return this.sizeBytes;
	}

}
