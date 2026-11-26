package com.nemaz.school.moderation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.moderation.ModerationTarget;
import com.nemaz.school.moderation.ReportTarget;
import com.nemaz.school.moderation.TargetRef;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The open registry of the modules that own reportable content: every {@link ReportTarget} and {@link ModerationTarget} bean
 * is picked up, so a later milestone (portfolio, tutors, library) adds its target types by registering a bean. A type with
 * no owner cannot be reported (400 {@code moderation.target_type_unsupported}).
 */
@Component
class TargetRegistry {

	private final ObjectProvider<ReportTarget> reportTargets;

	private final ObjectProvider<ModerationTarget> moderationTargets;

	TargetRegistry(ObjectProvider<ReportTarget> reportTargets, ObjectProvider<ModerationTarget> moderationTargets) {
		this.reportTargets = reportTargets;
		this.moderationTargets = moderationTargets;
	}

	private List<ReportTarget> ownersOf(String type) {
		List<ReportTarget> owners = new ArrayList<>();
		this.reportTargets.orderedStream().filter((t) -> t.targetTypes().contains(type)).forEach(owners::add);
		return owners;
	}

	boolean supported(String type) {
		return !ownersOf(type).isEmpty();
	}

	/** The target as the reporter may see it: the first owner that lets them see it; empty when none does. */
	Optional<TargetRef> resolveFor(CurrentUser reporter, String type, UUID id) {
		for (ReportTarget owner : ownersOf(type)) {
			Optional<TargetRef> ref = owner.resolveFor(reporter, type, id);
			if (ref.isPresent()) {
				return ref;
			}
		}
		return Optional.empty();
	}

	Optional<TargetRef> describe(String type, UUID id) {
		for (ReportTarget owner : ownersOf(type)) {
			Optional<TargetRef> ref = owner.describe(type, id);
			if (ref.isPresent()) {
				return ref;
			}
		}
		return Optional.empty();
	}

	void accepted(UUID reportId, UUID reporterId, TargetRef target, String reason, String details) {
		this.reportTargets.orderedStream()
			.filter((t) -> t.targetTypes().contains(target.type()))
			.forEach((t) -> t.onReportAccepted(reportId, reporterId, target, reason, details));
	}

	Optional<ModerationTarget> moderationTarget(String type) {
		return this.moderationTargets.orderedStream().filter((t) -> t.targetTypes().contains(type)).findFirst();
	}

}
