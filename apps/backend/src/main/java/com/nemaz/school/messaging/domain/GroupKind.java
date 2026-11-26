package com.nemaz.school.messaging.domain;

/** The kinds of group (rule book R6). {@link #type()} is the conversation type stored with it. */
public enum GroupKind {

	/** Students and teachers of one class; follows the roster. */
	CLASS_STUDENTS(ConversationType.CLASS),

	/** Guardians of the class's students and its teachers (no students); follows the roster and the guardian links. */
	CLASS_PARENTS(ConversationType.CLASS),

	STAFF(ConversationType.GROUP),

	/** Students of one class chosen by a teacher (or proposed by a student when the school approves them). */
	STUDENT_GROUP(ConversationType.GROUP),

	/** Announcement-style conversation of school staff. */
	SCHOOL_STAFF(ConversationType.SCHOOL);

	private final ConversationType type;

	GroupKind(ConversationType type) {
		this.type = type;
	}

	public ConversationType type() {
		return this.type;
	}

	/** Membership follows the class roster automatically. */
	public boolean rosterDriven() {
		return this == CLASS_STUDENTS || this == CLASS_PARENTS;
	}

	public boolean classBound() {
		return this == CLASS_STUDENTS || this == CLASS_PARENTS || this == STUDENT_GROUP;
	}

}
