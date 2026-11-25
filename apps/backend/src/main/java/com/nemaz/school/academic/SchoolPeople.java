package com.nemaz.school.academic;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.media.MediaSummary;

/**
 * What the academic module needs to know about people, implemented by the people module (so that
 * academic does not depend on it). Ids are user ids.
 */
public interface SchoolPeople {

	/** The user is an ACTIVE student of the school. */
	boolean isActiveStudent(UUID schoolId, UUID studentId);

	/** The user is ACTIVE TEACHER staff of the school. */
	boolean isActiveTeacher(UUID schoolId, UUID teacherId);

	/** Which of {@code studentIds} are ACTIVE students of the school. */
	java.util.Set<UUID> activeStudents(UUID schoolId, Collection<UUID> studentIds);

	/** Display data; unknown ids are absent. Never contains personal details beyond name and avatar. */
	Map<UUID, PersonCard> cards(Collection<UUID> userIds);

	/** Date of birth, gender and username of students, for callers who may see them (the caller decides). */
	Map<UUID, StudentPersonal> studentPersonal(Collection<UUID> studentIds);

	record PersonCard(UUID userId, String displayName, MediaSummary avatar, Map<String, Object> avatarConfig) {
	}

	record StudentPersonal(UUID studentId, LocalDate dateOfBirth, String gender, String username) {
	}

}
