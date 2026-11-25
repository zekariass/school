package com.nemaz.school.academic;

import java.util.List;
import java.util.UUID;

/**
 * What the roster import (imports module) needs from the academic module: the school's structure as lookup lists,
 * and writes of grade levels, subjects and classes on behalf of an administrator who has already been authorized
 * for the import ({@code school.import.manage}). No method checks permissions; writes join the caller's
 * transaction, publish the usual events ({@link ClassCreated}, {@link ClassMembershipChanged}) and audit. Class
 * rosters are changed through {@link ClassMemberships}. Academic years and periods are never written here.
 */
public interface StructureImports {

	List<YearInfo> years(UUID schoolId);

	List<GradeInfo> gradeLevels(UUID schoolId);

	List<SubjectInfo> subjects(UUID schoolId);

	/** Classes of the school (any status) with their current lead teacher, if any. */
	List<ClassInfo> classes(UUID schoolId);

	UUID createGradeLevel(UUID schoolId, UUID actorId, String name, String code, Integer sequence);

	void updateGradeLevel(UUID schoolId, UUID actorId, UUID gradeId, String name, String code, Integer sequence);

	UUID createSubject(UUID schoolId, UUID actorId, String name, String code, String color);

	void updateSubject(UUID schoolId, UUID actorId, UUID subjectId, String name, String code, String color);

	/** Creates a class; {@code leadTeacherId} (a user id, must be an ACTIVE teacher of the school) becomes its LEAD. */
	UUID createClass(UUID schoolId, UUID actorId, NewClass c);

	/**
	 * Changes grade level, campus and type (null = unchanged) and, when the class has no ACTIVE lead yet, makes
	 * {@code leadTeacherId} its LEAD. Returns true when the lead was set.
	 */
	boolean updateClass(UUID schoolId, UUID actorId, UUID classId, UUID gradeLevelId, UUID campusId, String classType,
			UUID leadTeacherId);

	record YearInfo(UUID id, String name, boolean current, String status) {
	}

	record GradeInfo(UUID id, String name, String code, int sequence, boolean active) {
	}

	record SubjectInfo(UUID id, String name, String code, String color, boolean active) {
	}

	record ClassInfo(UUID id, String name, UUID academicYearId, UUID gradeLevelId, UUID campusId, String classType,
			String status, UUID leadTeacherId) {
	}

	record NewClass(String name, UUID academicYearId, UUID gradeLevelId, UUID campusId, String classType,
			UUID leadTeacherId) {
	}

}
