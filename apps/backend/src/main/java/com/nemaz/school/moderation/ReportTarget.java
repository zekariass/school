package com.nemaz.school.moderation;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;

/**
 * Implemented by every module that owns reportable content, for its own target types (docs/Safeguarding_Rules.md S1.1).
 * The registry accepts several implementations of one type (a person is visible in a shared class and in a shared
 * conversation, owned by two modules): a target is reportable when any of them says so. Implementations must apply the
 * owner's own live visibility rules (custody-restricted and revoked guardians see nothing, so they cannot report), must not
 * reveal why a target is not visible, and never copy text.
 */
public interface ReportTarget {

	/** The target types this bean owns (see {@link TargetTypes}). */
	Set<String> targetTypes();

	/** The target as the reporter may see it; empty when they may not (the caller answers the same generic 404 as the content). */
	Optional<TargetRef> resolveFor(CurrentUser reporter, String targetType, UUID targetId);

	/** Facts about an existing target regardless of who asks (event consumption, the case workflow); empty when it is gone. */
	Optional<TargetRef> describe(String targetType, UUID targetId);

	/**
	 * Called once, in the reporting transaction, after the moderation module stored a NEW report that came through
	 * {@code POST /moderation/reports}, so the owner can keep its own record (messaging keeps {@code message_reports}, which
	 * its review path needs). Reports that arrive through the owner's own endpoint and event do not trigger it.
	 */
	default void onReportAccepted(UUID reportId, UUID reporterId, TargetRef target, String reasonCode, String details) {
	}

}
