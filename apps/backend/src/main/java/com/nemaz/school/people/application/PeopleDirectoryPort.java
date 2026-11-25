package com.nemaz.school.people.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserProfileView;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The academic module's {@link SchoolPeople} port: names, avatars and personal details of people. Kept apart from
 * {@link PeopleMembershipPorts}, which the media module's policies depend on (this one depends on media).
 */
@Component
@Transactional(readOnly = true)
class PeopleDirectoryPort implements SchoolPeople {

	private final JdbcClient jdbc;

	private final UserDirectory users;

	private final MediaService media;

	private final JsonMapper json;

	PeopleDirectoryPort(JdbcClient jdbc, UserDirectory users, MediaService media, JsonMapper json) {
		this.jdbc = jdbc;
		this.users = users;
		this.media = media;
		this.json = json;
	}

	// --- SchoolPeople -----------------------------------------------------------------------

	@Override
	public boolean isActiveStudent(UUID schoolId, UUID studentId) {
		return this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :u and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("u", studentId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean isActiveTeacher(UUID schoolId, UUID teacherId) {
		return this.jdbc.sql("""
				select exists (select 1 from school_staff where school_id = :s and user_id = :u and staff_type = 'TEACHER'
				               and status = 'ACTIVE')""")
			.param("s", schoolId)
			.param("u", teacherId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public Set<UUID> activeStudents(UUID schoolId, Collection<UUID> studentIds) {
		if (studentIds.isEmpty()) {
			return Set.of();
		}
		return new HashSet<>(this.jdbc
			.sql("select student_id from school_students where school_id = :s and status = 'ACTIVE' and student_id in (:ids)")
			.param("s", schoolId)
			.param("ids", studentIds)
			.query(UUID.class)
			.list());
	}

	@Override
	public Map<UUID, PersonCard> cards(Collection<UUID> userIds) {
		Map<UUID, PersonCard> cards = new HashMap<>();
		if (userIds.isEmpty()) {
			return cards;
		}
		Map<UUID, UserProfileView> profiles = this.users.profiles(userIds);
		Map<UUID, MediaSummary> avatars = this.media.summaries(profiles.values()
			.stream()
			.map(UserProfileView::avatarMediaId)
			.filter(java.util.Objects::nonNull)
			.toList());
		Map<UUID, Map<String, Object>> configs = avatarConfigs(userIds);
		profiles.forEach((id, p) -> cards.put(id, new PersonCard(id, p.displayName(),
				(p.avatarMediaId() != null) ? avatars.get(p.avatarMediaId()) : null, configs.get(id))));
		return cards;
	}

	@Override
	public Map<UUID, StudentPersonal> studentPersonal(Collection<UUID> studentIds) {
		Map<UUID, StudentPersonal> result = new HashMap<>();
		this.users.profiles(studentIds)
			.forEach((id, p) -> result.put(id, new StudentPersonal(id, p.dateOfBirth(), p.gender(), p.username())));
		return result;
	}

	@SuppressWarnings("unchecked")
	private Map<UUID, Map<String, Object>> avatarConfigs(Collection<UUID> userIds) {
		Map<UUID, Map<String, Object>> configs = new HashMap<>();
		this.jdbc.sql("select user_id, avatar_config::text as cfg from student_profiles where user_id in (:ids) and avatar_config is not null")
			.param("ids", userIds)
			.query((rs, i) -> {
				configs.put(Rows.uuid(rs, "user_id"), this.json.readValue(rs.getString("cfg"), Map.class));
				return null;
			})
			.list();
		return configs;
	}


}
