package com.nemaz.school.safeguarding;

import java.util.Set;
import java.util.UUID;

/** Who the safeguarding leads are, for the modules that must alert them or that depend on a school having one. */
public interface SafeguardingLeads {

	/** True when the school itself has at least one ACTIVE safeguarding lead (platform leads do not count). */
	boolean hasLead(UUID schoolId);

	/** The ACTIVE leads of the school. */
	Set<UUID> leadsOf(UUID schoolId);

	/**
	 * Who is alerted about a serious matter of the school: its leads, or the platform safeguarding leads when it has none
	 * (also for a null school, a platform-scope matter).
	 */
	Set<UUID> alertRecipients(UUID schoolId);

}
