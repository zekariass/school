package com.nemaz.school.moderation;

import java.util.List;

/** The reason codes of a report (docs/Safeguarding_Rules.md S1.2). */
public final class ReasonCodes {

	public static final String SPAM = "SPAM";

	public static final String HARASSMENT = "HARASSMENT";

	public static final String INAPPROPRIATE = "INAPPROPRIATE";

	public static final String SAFETY_CONCERN = "SAFETY_CONCERN";

	public static final String COPYRIGHT = "COPYRIGHT";

	public static final String PRIVACY = "PRIVACY";

	public static final String INACCURATE = "INACCURATE";

	public static final String OTHER = "OTHER";

	public static final List<String> ALL = List.of(SPAM, HARASSMENT, INAPPROPRIATE, SAFETY_CONCERN, COPYRIGHT, PRIVACY,
			INACCURATE, OTHER);

	private ReasonCodes() {
	}

}
