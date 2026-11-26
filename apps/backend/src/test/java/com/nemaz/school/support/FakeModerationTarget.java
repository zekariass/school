package com.nemaz.school.support;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.moderation.ModerationTarget;
import com.nemaz.school.moderation.ReportTarget;
import com.nemaz.school.moderation.ReviewContent;
import com.nemaz.school.moderation.TargetRef;

/**
 * A target of a later milestone (a library resource, which has no school: a platform-scope target) registered through the SPIs
 * only: proves that the registry is open and that moderation needs no change for a new target type. A test registers the
 * resources a user may see ({@link #visibleTo}) and reads what the owner was asked to do ({@link #calls}).
 */
public class FakeModerationTarget implements ReportTarget, ModerationTarget {

	public static final String TYPE = "LIBRARY_RESOURCE";

	private final ConcurrentHashMap<UUID, TargetRef> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<UUID, Set<UUID>> viewers = new ConcurrentHashMap<>();

	private final List<String> calls = new CopyOnWriteArrayList<>();

	/** Registers a resource authored by {@code author} that only {@code viewer} can see. */
	public UUID resource(UUID author, UUID viewer) {
		UUID id = UUID.randomUUID();
		this.resources.put(id, new TargetRef(TYPE, id, null, author));
		this.viewers.put(id, Set.of(viewer));
		return id;
	}

	public List<String> calls() {
		return List.copyOf(this.calls);
	}

	@Override
	public Set<String> targetTypes() {
		return Set.of(TYPE);
	}

	@Override
	public Optional<TargetRef> resolveFor(CurrentUser reporter, String targetType, UUID targetId) {
		return Optional.ofNullable(this.resources.get(targetId))
			.filter((ref) -> this.viewers.getOrDefault(targetId, Set.of()).contains(reporter.userId()));
	}

	@Override
	public Optional<TargetRef> describe(String targetType, UUID targetId) {
		return Optional.ofNullable(this.resources.get(targetId));
	}

	@Override
	public void hold(String targetType, UUID targetId, UUID caseId) {
		this.calls.add("hold " + targetId);
	}

	@Override
	public void takeDown(String targetType, UUID targetId, UUID caseId, UUID actorId, boolean removal) {
		this.calls.add((removal ? "remove " : "hide ") + targetId);
	}

	@Override
	public void restore(String targetType, UUID targetId, UUID caseId, UUID actorId) {
		this.calls.add("restore " + targetId);
	}

	@Override
	public ReviewContent read(String targetType, UUID targetId) {
		this.calls.add("read " + targetId);
		return ReviewContent.text(List.of());
	}

}
