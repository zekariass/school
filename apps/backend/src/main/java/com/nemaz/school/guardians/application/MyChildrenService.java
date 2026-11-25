package com.nemaz.school.guardians.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.academic.SchoolPeople.StudentPersonal;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.guardians.application.GuardianLinkService.LinkPermissions;
import com.nemaz.school.guardians.application.GuardianLinkService.LinkStatus;
import com.nemaz.school.media.MediaSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /me/children}: the caller's children through ACTIVE, not custody-restricted links, with their own record
 * (full: it is the guardian's own child), the schools where the child is ACTIVE and, when the link allows academics,
 * the child's ACTIVE classes with their teachers' names. PENDING links are listed apart with nothing about the child
 * but the first name. REJECTED, REVOKED and restricted links do not appear.
 */
@Service
@Transactional(readOnly = true)
public class MyChildrenService {

	private final JdbcClient jdbc;

	private final SchoolPeople people;

	MyChildrenService(JdbcClient jdbc, SchoolPeople people) {
		this.jdbc = jdbc;
		this.people = people;
	}

	public MyChildren list(CurrentUser user) {
		List<GuardianLinkService.LinkRow> links = this.jdbc
			.sql(GuardianLinkService.SELECT + " where sg.guardian_id = :g and sg.status in ('ACTIVE', 'PENDING')"
					+ " and not sg.custody_restricted order by sg.created_at, sg.id")
			.param("g", user.userId())
			.query(GuardianLinkService::row)
			.list();
		List<GuardianLinkService.LinkRow> active = links.stream().filter((l) -> l.status() == LinkStatus.ACTIVE).toList();
		List<UUID> childIds = active.stream().map(GuardianLinkService.LinkRow::studentId).toList();
		Map<UUID, PersonCard> cards = this.people.cards(childIds);
		Map<UUID, StudentPersonal> personal = this.people.studentPersonal(childIds);
		List<Child> children = new ArrayList<>();
		for (GuardianLinkService.LinkRow link : active) {
			PersonCard card = cards.get(link.studentId());
			StudentPersonal p = personal.get(link.studentId());
			List<ChildSchool> schools = schools(link.studentId(), link.permissions().canViewAcademics());
			if (card == null || schools.isEmpty()) {
				continue;
			}
			children.add(new Child(link.studentId(), link.id(), card.displayName(), givenName(link.studentId()),
					card.avatar(), card.avatarConfig(), (p != null) ? p.dateOfBirth() : null, (p != null) ? p.gender() : null,
					link.relationshipType(), link.primary(), link.permissions(), schools));
		}
		List<PendingLink> pending = new ArrayList<>();
		for (GuardianLinkService.LinkRow link : links) {
			if (link.status() == LinkStatus.PENDING) {
				pending.add(new PendingLink(link.id(), givenName(link.studentId()), schoolOfInvitation(link.invitationId()),
						link.status(), link.createdAt()));
			}
		}
		return new MyChildren(children, pending);
	}

	private List<ChildSchool> schools(UUID studentId, boolean withClasses) {
		List<ChildSchool> result = new ArrayList<>();
		List<Object[]> rows = this.jdbc.sql("""
				select ss.school_id, s.slug, s.name, ss.student_number from school_students ss join schools s on s.id = ss.school_id
				where ss.student_id = :st and ss.status = 'ACTIVE' order by ss.created_at, ss.id""")
			.param("st", studentId)
			.query((rs, i) -> new Object[] { Rows.uuid(rs, "school_id"), rs.getString("slug"), rs.getString("name"),
					rs.getString("student_number") })
			.list();
		for (Object[] row : rows) {
			UUID schoolId = (UUID) row[0];
			result.add(new ChildSchool(schoolId, (String) row[1], (String) row[2], (String) row[3],
					withClasses ? classes(studentId, schoolId) : List.of()));
		}
		return result;
	}

	private List<ChildClass> classes(UUID studentId, UUID schoolId) {
		record ClassRow(UUID id, String name, String icon, String color, UUID gradeId, String gradeName) {
		}
		List<ClassRow> rows = this.jdbc.sql("""
				select c.id, c.name, c.icon, c.color, c.grade_level_id, gl.name as grade_name
				from class_students cs join classes c on c.id = cs.class_id
				left join grade_levels gl on gl.id = c.grade_level_id
				where cs.student_id = :st and cs.status = 'ACTIVE' and c.school_id = :s and c.status = 'ACTIVE'
				order by c.name, c.id""")
			.param("st", studentId)
			.param("s", schoolId)
			.query((rs, i) -> new ClassRow(Rows.uuid(rs, "id"), rs.getString("name"), rs.getString("icon"),
					rs.getString("color"), Rows.uuid(rs, "grade_level_id"), rs.getString("grade_name")))
			.list();
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<Object[]>> teachersByClass = new LinkedHashMap<>();
		Set<UUID> teacherIds = new LinkedHashSet<>();
		this.jdbc.sql("""
				select class_id, teacher_id, role from class_teachers where class_id in (:ids) and status = 'ACTIVE'
				order by case role when 'LEAD' then 0 when 'CO_TEACHER' then 1 when 'SUBJECT_TEACHER' then 2 else 3 end,
				         joined_at, id""")
			.param("ids", rows.stream().map(ClassRow::id).toList())
			.query((rs, i) -> {
				UUID teacher = Rows.uuid(rs, "teacher_id");
				teacherIds.add(teacher);
				teachersByClass.computeIfAbsent(Rows.uuid(rs, "class_id"), (k) -> new ArrayList<>())
					.add(new Object[] { teacher, rs.getString("role") });
				return teacher;
			})
			.list();
		Map<UUID, PersonCard> cards = this.people.cards(teacherIds);
		List<ChildClass> result = new ArrayList<>();
		for (ClassRow row : rows) {
			List<ClassTeacher> teachers = new ArrayList<>();
			for (Object[] t : teachersByClass.getOrDefault(row.id(), List.of())) {
				PersonCard card = cards.get((UUID) t[0]);
				if (card != null && teachers.stream().noneMatch((x) -> x.teacherId().equals(t[0]))) {
					teachers.add(new ClassTeacher((UUID) t[0], card.displayName(), TeacherRole.valueOf((String) t[1])));
				}
			}
			result.add(new ChildClass(row.id(), row.name(), row.icon(), row.color(), row.gradeId(), row.gradeName(),
					teachers));
		}
		return result;
	}

	private String givenName(UUID userId) {
		return this.jdbc.sql("select given_name from users where id = :id")
			.param("id", userId)
			.query(String.class)
			.optional()
			.orElse("");
	}

	private String schoolOfInvitation(UUID invitationId) {
		if (invitationId == null) {
			return null;
		}
		return this.jdbc.sql("select s.name from invitations i join schools s on s.id = i.school_id where i.id = :id")
			.param("id", invitationId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	public record MyChildren(@NotNull List<Child> children,
			@NotNull @Schema(description = "Link requests waiting for the school's verification") List<PendingLink> pending) {
	}

	/** The guardian's own child: the full record is fine here. */
	public record Child(@NotNull UUID studentId, @NotNull UUID linkId, @NotNull String displayName,
			@NotNull String givenName, MediaSummary avatar, Map<String, Object> avatarConfig, LocalDate dateOfBirth,
			String gender, @NotNull RelationshipType relationshipType, @NotNull boolean primary,
			@NotNull LinkPermissions permissions, @NotNull List<ChildSchool> schools) {
	}

	public record ChildSchool(@NotNull UUID schoolId, @NotNull String schoolSlug, @NotNull String schoolName,
			String studentNumber,
			@NotNull @Schema(description = "Empty unless the link allows viewing academics") List<ChildClass> classes) {
	}

	public record ChildClass(@NotNull UUID classId, @NotNull String name, String icon, String color, UUID gradeLevelId,
			String gradeLevelName, @NotNull List<ClassTeacher> teachers) {
	}

	/** Names only: guardians do not see staff avatars (M5). */
	public record ClassTeacher(@NotNull UUID teacherId, @NotNull String displayName, @NotNull TeacherRole role) {
	}

	public record PendingLink(@NotNull UUID linkId, @NotNull String childFirstName, String schoolName,
			@NotNull LinkStatus status, @NotNull Instant requestedAt) {
	}

}
