package com.nemaz.school.safeguarding;

import java.util.UUID;

/**
 * How a moderation case becomes a safeguarding incident (docs/Safeguarding_Rules.md S4.1): a moderator escalating a case, or
 * a CRITICAL SAFETY_CONCERN. The caller has checked who may escalate; the narrative is what the escalating person wrote (it is
 * encrypted here and never stored elsewhere). Idempotent per case: a case that already has an incident returns that incident.
 */
public interface IncidentIntake {

	/**
	 * @param schoolId the case's school (null: a platform incident, read by platform leads)
	 * @param category CONDUCT, ONLINE_SAFETY, WELLBEING, TUTOR_CONDUCT, DATA_BREACH or OTHER
	 * @param severity LOW, MEDIUM, HIGH or CRITICAL
	 * @return the incident id
	 */
	UUID raiseFromCase(UUID schoolId, UUID caseId, UUID actorId, String category, String severity, String narrative);

}
