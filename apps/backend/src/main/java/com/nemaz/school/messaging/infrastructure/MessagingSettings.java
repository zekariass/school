package com.nemaz.school.messaging.infrastructure;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.messaging.domain.OfficeHours;
import com.nemaz.school.tenancy.FeatureService;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The {@code messaging.*} settings of a school and the {@code messaging} feature flag. Every default is the restrictive one
 * (students cannot start anything, no student-student or parent-parent direct messages, no translation, no images by students).
 */
@Component
public class MessagingSettings {

	public static final String FEATURE = "messaging";

	static final String PARENT_TEACHER = "messaging.parent_teacher.enabled";

	static final String PARENT_PARENT = "messaging.parent_parent.enabled";

	static final String STUDENT_ENABLED = "messaging.student.enabled";

	static final String STUDENT_MIN_AGE = "messaging.student.min_age";

	static final String TEACHER_DIRECT = "messaging.student.teacher_direct.enabled";

	static final String TEACHER_DIRECT_INITIATOR = "messaging.student.teacher_direct.initiator";

	static final String STUDENT_DIRECT = "messaging.student.student_direct.enabled";

	static final String STUDENT_GROUP_CREATE = "messaging.student.group_create";

	static final String GROUP_CREATE_ROLES = "messaging.group_create.roles";

	static final String TEACHER_HOURS = "messaging.teacher_hours";

	static final String EDIT_WINDOW = "messaging.edit_window_minutes";

	static final String STUDENT_MEDIA = "messaging.student.media_enabled";

	static final String TRANSLATION = "messaging.translation.enabled";

	private static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Addis_Ababa");

	private final SettingsService settings;

	private final FeatureService features;

	private final JdbcClient jdbc;

	MessagingSettings(SettingsService settings, FeatureService features, JdbcClient jdbc) {
		this.settings = settings;
		this.features = features;
		this.jdbc = jdbc;
	}

	/** The settings the SQL of {@link Eligibility} needs, one row per school. */
	public record SchoolPolicy(UUID schoolId, boolean studentsEnabled, Integer minAge, boolean parentTeacher,
			boolean parentParent, boolean teacherDirect, boolean studentDirect, boolean featureEnabled) {
	}

	public SchoolPolicy policy(UUID schoolId) {
		SettingScope scope = SettingScope.school(schoolId);
		return new SchoolPolicy(schoolId, this.settings.getBoolean(STUDENT_ENABLED, scope),
				this.settings.getInt(STUDENT_MIN_AGE, scope).orElse(null), this.settings.getBoolean(PARENT_TEACHER, scope),
				this.settings.getBoolean(PARENT_PARENT, scope), this.settings.getBoolean(TEACHER_DIRECT, scope),
				this.settings.getBoolean(STUDENT_DIRECT, scope), featureEnabled(schoolId));
	}

	public List<SchoolPolicy> policies(Collection<UUID> schoolIds) {
		return new LinkedHashSet<>(schoolIds).stream().map(this::policy).toList();
	}

	public boolean featureEnabled(UUID schoolId) {
		return this.features.isEnabled(FEATURE, schoolId);
	}

	/** True when students may also start a teacher-student conversation ({@code initiator = EITHER}). */
	public boolean studentMayStartTeacherDirect(UUID schoolId) {
		return "EITHER".equals(this.settings.getString(TEACHER_DIRECT_INITIATOR, SettingScope.school(schoolId)).orElse("TEACHER_ONLY"));
	}

	public boolean studentsMayProposeGroups(UUID schoolId) {
		return "STUDENTS_WITH_APPROVAL"
			.equals(this.settings.getString(STUDENT_GROUP_CREATE, SettingScope.school(schoolId)).orElse("TEACHER_ONLY"));
	}

	/** The role codes allowed to create groups ({@code TEACHER}, {@code SCHOOL_ADMIN}, ...). */
	public Set<String> groupCreateRoles(UUID schoolId) {
		return new LinkedHashSet<>(this.settings.getStringList(GROUP_CREATE_ROLES, SettingScope.school(schoolId)));
	}

	public int editWindowMinutes(UUID schoolId) {
		return this.settings.getInt(EDIT_WINDOW, SettingScope.school(schoolId)).orElse(15);
	}

	public boolean studentMedia(UUID schoolId) {
		return this.settings.getBoolean(STUDENT_MEDIA, SettingScope.school(schoolId));
	}

	public boolean translation(UUID schoolId) {
		return this.settings.getBoolean(TRANSLATION, SettingScope.school(schoolId));
	}

	/** The raw shape of {@code messaging.teacher_hours}. */
	public record TeacherHoursSetting(List<Integer> days, String start, String end) {
	}

	public Optional<OfficeHours> officeHours(UUID schoolId) {
		return this.settings.getObject(TEACHER_HOURS, SettingScope.school(schoolId), TeacherHoursSetting.class)
			.map((h) -> new OfficeHours(h.days(), LocalTime.parse(h.start()), LocalTime.parse(h.end())));
	}

	/** The school's time zone (default Africa/Addis_Ababa when it is missing or unknown). */
	public ZoneId zone(UUID schoolId) {
		String zone = this.jdbc.sql("select timezone from schools where id = :id")
			.param("id", schoolId)
			.query(String.class)
			.optional()
			.orElse(null);
		try {
			return (zone != null) ? ZoneId.of(zone) : DEFAULT_ZONE;
		}
		catch (DateTimeException ex) {
			return DEFAULT_ZONE;
		}
	}

}
