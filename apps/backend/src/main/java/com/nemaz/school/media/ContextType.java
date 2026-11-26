package com.nemaz.school.media;

/** The kind of object a piece of media belongs to; more arrive with classes, posts and so on. */
public enum ContextType {

	USER, SCHOOL, ORGANIZATION, CLASS;

	/** First segment of object keys. */
	public String keySegment() {
		return switch (this) {
			case USER -> "users";
			case SCHOOL -> "schools";
			case ORGANIZATION -> "organizations";
			case CLASS -> "classes";
		};
	}

}
