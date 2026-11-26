package com.nemaz.school.messaging.domain;

public enum MemberRole {

	OWNER, MODERATOR, MEMBER,

	/** A guardian who can read, never write (the safeguarding floor). */
	OBSERVER;

	public boolean moderates() {
		return this == OWNER || this == MODERATOR;
	}

}
