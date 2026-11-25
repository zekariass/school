package com.nemaz.school.academic;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Roster operations for the people module: it ends memberships when a student is withdrawn or a teacher
 * deactivated, adds a student created inline to a class, and asks who is in which class. All methods join
 * the caller's transaction, publish {@link ClassMembershipChanged} and need no permission check (the caller
 * has made it).
 */
public interface ClassMemberships {

	/** Adds the student as ACTIVE member. 404 unknown class, 409 archived class or already a member. */
	void addStudent(UUID schoolId, UUID classId, UUID studentId, UUID actorId);

	/** Ends every live membership of the student in the school's classes ({@code reason}: LEFT_SCHOOL, ...); returns how many. */
	int endStudentMemberships(UUID schoolId, UUID studentId, String reason, UUID actorId);

	/** Ends every live class_teachers row of the teacher in the school (status FORMER); returns how many. */
	int endTeacherMemberships(UUID schoolId, UUID teacherId, UUID actorId);

	/** True when the teacher is ACTIVE in a class in which the student is ACTIVE. */
	boolean teachesStudent(UUID teacherId, UUID studentId);

	/** True when both users are ACTIVE in one class, as students or teachers. */
	boolean shareActiveClass(UUID userA, UUID userB);

	/** ACTIVE student ids of one class. */
	Set<UUID> activeStudentIds(UUID classId);

	/** ACTIVE students of the school's classes of one grade level (any status of class). */
	Set<UUID> activeStudentIdsInGrade(UUID schoolId, UUID gradeLevelId);

	/** ACTIVE students of the classes the teacher is ACTIVE in. */
	Set<UUID> activeStudentIdsOfTeacher(UUID teacherId);

	/** For each student, the ACTIVE classes. */
	Map<UUID, List<StudentClass>> activeClassesOf(Collection<UUID> studentIds);

	record StudentClass(UUID classId, String name, UUID gradeLevelId) {
	}

}
