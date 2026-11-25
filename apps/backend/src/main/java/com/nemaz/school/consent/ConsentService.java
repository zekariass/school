package com.nemaz.school.consent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The effective consent of a child, for every module that publishes or processes children's data (M8 media, later
 * recording and AI). Read-only; computed from the database on every call (no cache), so a withdrawal applies at once.
 * <p>
 * <b>Rule (most restrictive wins):</b> take the latest decision of each current guardian of the child, i.e. each ACTIVE
 * link without a custody restriction and with {@code can_give_consent} (in-app decisions and paper decisions recorded
 * by the school for that link), plus the latest paper decision of each named guardian without an account. If any of
 * them is DENIED or WITHDRAWN, the status is DENIED; otherwise, if at least one is GRANTED on a version that is not
 * superseded by a published version with {@code requires_reconsent}, it is GRANTED; otherwise UNKNOWN. Decisions of
 * revoked, rejected, pending or custody-restricted links do not count.
 * <p>
 * <b>Callers must treat UNKNOWN as "no" for MEDIA_PUBLICATION</b> (R-09): use {@link #mediaPublicationAllowed}.
 */
public interface ConsentService {

	ConsentStatus status(UUID studentId, UUID schoolId, ConsentType type);

	/** {@link #status} for several children of one school (unknown ids are UNKNOWN). */
	Map<UUID, ConsentStatus> statuses(Collection<UUID> studentIds, UUID schoolId, ConsentType type);

	/** True only when MEDIA_PUBLICATION is GRANTED; UNKNOWN and DENIED are both false. */
	default boolean mediaPublicationAllowed(UUID studentId, UUID schoolId) {
		return status(studentId, schoolId, ConsentType.MEDIA_PUBLICATION).granted();
	}

}
