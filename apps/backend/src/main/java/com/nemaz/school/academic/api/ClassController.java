package com.nemaz.school.academic.api;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.application.ClassRosterService;
import com.nemaz.school.academic.application.ClassRosterService.ClassTeacherView;
import com.nemaz.school.academic.application.ClassRosterService.GroupDraft;
import com.nemaz.school.academic.application.ClassRosterService.LeftReason;
import com.nemaz.school.academic.application.ClassRosterService.RosterStudent;
import com.nemaz.school.academic.application.ClassRosterService.StudentGroupView;
import com.nemaz.school.academic.application.ClassService;
import com.nemaz.school.academic.application.ClassService.ClassFilter;
import com.nemaz.school.academic.application.ClassService.ClassStatus;
import com.nemaz.school.academic.application.ClassService.ClassType;
import com.nemaz.school.academic.application.ClassService.ClassView;
import com.nemaz.school.academic.application.ClassService.CreateClass;
import com.nemaz.school.academic.application.ClassService.UpdateClass;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Classes and their rosters. Everything is authorized by {@code ClassAccess}: a class the caller cannot see
 * is 404, one they can see but may not change is 403 (see the service comments for who may do what).
 */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/classes")
@Tag(name = "classes")
public class ClassController {

	private static final String HEX_COLOR = "^#[0-9a-fA-F]{6}$";

	private final ClassService classes;

	private final ClassRosterService roster;

	ClassController(ClassService classes, ClassRosterService roster) {
		this.classes = classes;
		this.roster = roster;
	}

	@GetMapping
	@Operation(operationId = "listClasses",
			summary = "Classes the caller may see (students and teachers: their own; administrators: all in scope), by name; filters year, grade, campus, status, type, mine, q")
	public CursorPage<ClassView> list(@PathVariable UUID schoolId, @RequestParam(required = false) UUID academicYearId,
			@RequestParam(required = false) UUID gradeLevelId, @RequestParam(required = false) UUID campusId,
			@RequestParam(required = false) ClassStatus status, @RequestParam(required = false) ClassType classType,
			@RequestParam(required = false, defaultValue = "false") boolean mine, @RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
		return this.classes.list(CurrentUser.require(), schoolId,
				new ClassFilter(academicYearId, gradeLevelId, campusId, status, classType, mine, q), cursor, limit);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createClass",
			summary = "Create a class: needs class.manage, or be a teacher of the school while classes.teachers_can_create is on (the teacher becomes lead)")
	public ClassView create(@PathVariable UUID schoolId, @Valid @RequestBody CreateClassRequest r) {
		return this.classes.create(CurrentUser.require(), schoolId, new CreateClass(r.name(), r.academicYearId(),
				r.gradeLevelId(), r.campusId(), r.classType(), r.icon(), r.color(), r.subjectIds(), r.studentIds()));
	}

	@GetMapping("/{classId}")
	@Operation(operationId = "getClass", summary = "One class the caller may see; 404 otherwise")
	public ClassView get(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.classes.get(CurrentUser.require(), schoolId, classId);
	}

	@PatchMapping("/{classId}")
	@Operation(operationId = "updateClass",
			summary = "Change a class (lead teacher or class.manage; campus and year only with class.manage); subjectIds replaces the set")
	public ClassView update(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody UpdateClassRequest r) {
		return this.classes.update(CurrentUser.require(), schoolId, classId, new UpdateClass(r.name(),
				r.academicYearId(), r.gradeLevelId(), r.campusId(), r.classType(), r.icon(), r.color(), r.subjectIds()));
	}

	@PostMapping("/{classId}/archive")
	@Operation(operationId = "archiveClass",
			summary = "Archive a class: it stays readable and keeps its members, but its roster can no longer change")
	public ClassView archive(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.classes.archive(CurrentUser.require(), schoolId, classId);
	}

	@PostMapping("/{classId}/restore")
	@Operation(operationId = "restoreClass", summary = "Make an archived class active again")
	public ClassView restore(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.classes.restore(CurrentUser.require(), schoolId, classId);
	}

	@DeleteMapping("/{classId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteClass",
			summary = "Delete a class that never had students (and no teacher besides its first lead); otherwise 409 class.not_empty, archive it instead")
	public void delete(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		this.classes.delete(CurrentUser.require(), schoolId, classId);
	}

	// --- students ---------------------------------------------------------------------------

	@GetMapping("/{classId}/students")
	@Operation(operationId = "listClassStudents",
			summary = "The class's ACTIVE students by name (at most 500, no paging). Date of birth, gender and username only for teachers of the class and school.students.manage (accessLevel FULL); everybody else gets name and avatar")
	public List<RosterStudent> students(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.roster.students(CurrentUser.require(), schoolId, classId);
	}

	@PostMapping("/{classId}/students")
	@Operation(operationId = "addClassStudents",
			summary = "Add students of the school to the class (all or none; 409 when one is not enrolled or already a member)")
	public List<RosterStudent> addStudents(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody AddStudentsRequest r) {
		return this.roster.addStudents(CurrentUser.require(), schoolId, classId, r.studentIds());
	}

	@DeleteMapping("/{classId}/students/{studentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "removeClassStudent",
			summary = "End a student's membership (history is kept); reason REMOVED (default) or PROMOTED")
	public void removeStudent(@PathVariable UUID schoolId, @PathVariable UUID classId, @PathVariable UUID studentId,
			@RequestParam(required = false) LeftReason reason) {
		this.roster.removeStudent(CurrentUser.require(), schoolId, classId, studentId, reason);
	}

	@PostMapping("/{classId}/students/{studentId}/move")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "moveClassStudent",
			summary = "Move a student to another class of the school: ends this membership (TRANSFERRED_CLASS) and starts the new one, atomically")
	public void moveStudent(@PathVariable UUID schoolId, @PathVariable UUID classId, @PathVariable UUID studentId,
			@Valid @RequestBody MoveStudentRequest r) {
		this.roster.moveStudent(CurrentUser.require(), schoolId, classId, studentId, r.toClassId());
	}

	// --- teachers ---------------------------------------------------------------------------

	@GetMapping("/{classId}/teachers")
	@Operation(operationId = "listClassTeachers", summary = "The class's ACTIVE teachers: lead first")
	public List<ClassTeacherView> teachers(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.roster.teachers(CurrentUser.require(), schoolId, classId);
	}

	@PostMapping("/{classId}/teachers")
	@Operation(operationId = "addClassTeacher",
			summary = "Add a teacher of the school (lead teacher or class.manage); SUBJECT_TEACHER needs subjectId")
	public List<ClassTeacherView> addTeacher(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody AddTeacherRequest r) {
		return this.roster.addTeacher(CurrentUser.require(), schoolId, classId, r.teacherId(), r.role(), r.subjectId());
	}

	@DeleteMapping("/{classId}/teachers/{classTeacherId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "removeClassTeacher",
			summary = "Remove a teacher row (lead or class.manage; any non-lead teacher may remove herself); the last lead cannot be removed (409 class.last_lead)")
	public void removeTeacher(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@PathVariable UUID classTeacherId) {
		this.roster.removeTeacher(CurrentUser.require(), schoolId, classId, classTeacherId);
	}

	// --- groups -----------------------------------------------------------------------------

	@GetMapping("/{classId}/groups")
	@Operation(operationId = "listClassGroups", summary = "Saved student groups of the class (teachers of the class)")
	public List<StudentGroupView> groups(@PathVariable UUID schoolId, @PathVariable UUID classId) {
		return this.roster.groups(CurrentUser.require(), schoolId, classId);
	}

	@PostMapping("/{classId}/groups")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createClassGroup", summary = "Save a group of the class's students (a result of the group maker)")
	public StudentGroupView createGroup(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody CreateGroupRequest r) {
		return this.roster.createGroup(CurrentUser.require(), schoolId, classId, r.name(), r.studentIds());
	}

	@PostMapping("/{classId}/group-sets")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createClassGroupSet",
			summary = "Save a whole group set (one run of the group maker) at once: all groups are created or none. 409 class.group_member_not_in_class when a member is not an ACTIVE student of the class")
	public List<StudentGroupView> createGroupSet(@PathVariable UUID schoolId, @PathVariable UUID classId,
			@Valid @RequestBody CreateGroupSetRequest r) {
		return this.roster.createGroupSet(CurrentUser.require(), schoolId, classId, r.groups()
			.stream()
			.map((g) -> new GroupDraft(g.name(), g.studentIds()))
			.toList());
	}

	@PatchMapping("/{classId}/groups/{groupId}")
	@Operation(operationId = "updateClassGroup",
			summary = "Rename a saved group and/or replace its members (absent fields stay)")
	public StudentGroupView updateGroup(@PathVariable UUID schoolId, @PathVariable UUID classId, @PathVariable UUID groupId,
			@Valid @RequestBody UpdateGroupRequest r) {
		return this.roster.updateGroup(CurrentUser.require(), schoolId, classId, groupId, r.name(), r.studentIds());
	}

	@DeleteMapping("/{classId}/groups/{groupId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteClassGroup", summary = "Delete a saved group")
	public void deleteGroup(@PathVariable UUID schoolId, @PathVariable UUID classId, @PathVariable UUID groupId) {
		this.roster.deleteGroup(CurrentUser.require(), schoolId, classId, groupId);
	}

	// --- requests ---------------------------------------------------------------------------

	public record CreateClassRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			UUID academicYearId, UUID gradeLevelId, UUID campusId, ClassType classType,
			@Size(max = 64, message = "validation.tooLong") String icon,
			@Pattern(regexp = HEX_COLOR, message = "validation.invalid") String color,
			@Size(max = 50, message = "validation.tooLong") List<@NotNull UUID> subjectIds,
			@Size(max = 200, message = "validation.tooLong") List<@NotNull UUID> studentIds) {
	}

	public record UpdateClassRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String name,
			UUID academicYearId, UUID gradeLevelId, UUID campusId, ClassType classType,
			@Size(max = 64, message = "validation.tooLong") String icon,
			@Pattern(regexp = HEX_COLOR, message = "validation.invalid") String color,
			@Size(max = 50, message = "validation.tooLong") List<@NotNull UUID> subjectIds) {
	}

	public record AddStudentsRequest(
			@NotEmpty(message = "validation.required") @Size(max = 200, message = "validation.tooLong") List<@NotNull UUID> studentIds) {
	}

	public record MoveStudentRequest(@NotNull(message = "validation.required") UUID toClassId) {
	}

	public record AddTeacherRequest(@NotNull(message = "validation.required") UUID teacherId,
			@NotNull(message = "validation.required") TeacherRole role, UUID subjectId) {
	}

	public record CreateGroupSetRequest(
			@NotEmpty(message = "validation.required") @Size(max = 50, message = "validation.tooLong") List<@Valid @NotNull CreateGroupRequest> groups) {
	}

	public record UpdateGroupRequest(@Size(min = 1, max = 100, message = "validation.tooLong") String name,
			@Size(min = 1, max = 200, message = "validation.tooLong") List<@NotNull UUID> studentIds) {
	}

	public record CreateGroupRequest(
			@NotBlank(message = "validation.required") @Size(max = 100, message = "validation.tooLong") String name,
			@NotEmpty(message = "validation.required") @Size(max = 200, message = "validation.tooLong") List<@NotNull UUID> studentIds) {
	}

}
