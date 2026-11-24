package com.nemaz.school.common.persistence;

import java.time.Instant;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The {@code -- + std} columns of Database_Design §1: {@code created_at}, {@code updated_at}
 * and {@code version} (optimistic locking, also used for offline-sync conflicts).
 * Timestamps come from the injected {@link java.time.Clock} through Spring Data auditing
 * ({@link PersistenceConfig}), so they are testable and always UTC.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity extends IdentifiedEntity {

	@Version
	private int version;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	public int getVersion() {
		return this.version;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
