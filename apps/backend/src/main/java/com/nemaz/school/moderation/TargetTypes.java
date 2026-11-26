package com.nemaz.school.moderation;

import java.util.List;

/** What can be reported (Database_Design §4.17). Later milestones register the owners of the types they add. */
public final class TargetTypes {

	public static final String POST = "POST";

	public static final String POST_COMMENT = "POST_COMMENT";

	public static final String STORY = "STORY";

	public static final String MESSAGE = "MESSAGE";

	public static final String USER = "USER";

	public static final String PORTFOLIO_ITEM = "PORTFOLIO_ITEM";

	public static final String TUTOR_PROFILE = "TUTOR_PROFILE";

	public static final String COURSE = "COURSE";

	public static final String TUTOR_REVIEW = "TUTOR_REVIEW";

	public static final String LIBRARY_RESOURCE = "LIBRARY_RESOURCE";

	public static final String MEDIA = "MEDIA";

	public static final String AI_OUTPUT = "AI_OUTPUT";

	public static final List<String> ALL = List.of(POST, POST_COMMENT, STORY, MESSAGE, USER, PORTFOLIO_ITEM, TUTOR_PROFILE,
			COURSE, TUTOR_REVIEW, LIBRARY_RESOURCE, MEDIA, AI_OUTPUT);

	private TargetTypes() {
	}

}
