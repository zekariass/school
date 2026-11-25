package com.nemaz.school.academic;

import java.util.UUID;

/**
 * A student or teacher joined, left or moved within a class roster (published in the changing
 * transaction; delivered at least once). {@code memberId} is the student's or teacher's user id;
 * {@code reason} is the roster's left reason (TRANSFERRED_CLASS, LEFT_SCHOOL, REMOVED, ...) for
 * {@link Change#LEFT}, otherwise null. A move produces a LEFT (TRANSFERRED_CLASS) for the old class and
 * a JOINED for the new one.
 */
public record ClassMembershipChanged(UUID classId, UUID schoolId, UUID memberId, MemberType memberType, Change change,
		String teacherRole, String reason, UUID actorId) {

	public enum MemberType {

		STUDENT, TEACHER

	}

	public enum Change {

		JOINED, LEFT

	}

}
