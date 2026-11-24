package com.nemaz.school.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One audit row to write. Build with {@link #of} and the fluent setters:
 * <pre>
 * audit.record(AuditEntry.of("school.create", AuditOutcome.SUCCESS).target("school", id).school(id)
 *     .metadata("slug", slug));
 * </pre>
 */
public final class AuditEntry {

	private final String action;

	private final AuditOutcome outcome;

	private String targetType;

	private UUID targetId;

	private UUID schoolId;

	private UUID actorUserId;

	private boolean actorOverridden;

	private Map<String, Object> changes;

	private final Map<String, Object> metadata = new LinkedHashMap<>();

	private AuditEntry(String action, AuditOutcome outcome) {
		this.action = action;
		this.outcome = outcome;
	}

	/** {@code action} is {@code <resource>.<verb>}, e.g. {@code school.create}, {@code auth.otp.verify}. */
	public static AuditEntry of(String action, AuditOutcome outcome) {
		if (action == null || action.isBlank() || outcome == null) {
			throw new IllegalArgumentException("Audit action and outcome are required");
		}
		return new AuditEntry(action, outcome);
	}

	public AuditEntry target(String type, UUID id) {
		this.targetType = type;
		this.targetId = id;
		return this;
	}

	public AuditEntry school(UUID id) {
		this.schoolId = id;
		return this;
	}

	/**
	 * The acting user when it is not the current request's user (sign-in: the user who just
	 * signed in; system jobs: null).
	 */
	public AuditEntry actor(UUID userId) {
		this.actorUserId = userId;
		this.actorOverridden = true;
		return this;
	}

	public AuditEntry changes(Object before, Object after) {
		Map<String, Object> diff = new LinkedHashMap<>();
		diff.put("before", before);
		diff.put("after", after);
		this.changes = diff;
		return this;
	}

	public AuditEntry metadata(String key, Object value) {
		if (value != null) {
			this.metadata.put(key, value);
		}
		return this;
	}

	public AuditEntry metadata(Map<String, ?> values) {
		if (values != null) {
			values.forEach(this::metadata);
		}
		return this;
	}

	public String action() {
		return this.action;
	}

	public AuditOutcome outcome() {
		return this.outcome;
	}

	public String targetType() {
		return this.targetType;
	}

	public UUID targetId() {
		return this.targetId;
	}

	public UUID schoolId() {
		return this.schoolId;
	}

	public UUID actorUserId() {
		return this.actorUserId;
	}

	public boolean actorOverridden() {
		return this.actorOverridden;
	}

	public Map<String, Object> changes() {
		return this.changes;
	}

	public Map<String, Object> metadata() {
		return this.metadata;
	}

}
