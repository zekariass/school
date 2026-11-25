package com.nemaz.school.imports.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.StructureImports;
import com.nemaz.school.academic.StructureImports.ClassInfo;
import com.nemaz.school.academic.StructureImports.GradeInfo;
import com.nemaz.school.academic.StructureImports.NewClass;
import com.nemaz.school.academic.StructureImports.YearInfo;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.SchoolLookups.Loaded;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.people.PeopleImports.StaffSnapshot;
import com.nemaz.school.tenancy.TenantDirectory.CampusEntry;

import org.springframework.stereotype.Component;

import static com.nemaz.school.imports.application.HandlerSupport.bounded;
import static com.nemaz.school.imports.application.HandlerSupport.phone;
import static com.nemaz.school.imports.application.HandlerSupport.str;
import static com.nemaz.school.imports.application.HandlerSupport.uuid;

/**
 * Classes: one per (name, academic year); the year is the current one unless the file names it. Grade levels and
 * campuses are looked up by name or code and must exist (import them first). A lead teacher is set only on a class
 * that has none.
 */
@Component
class ClassHandler implements RowHandler {

	private final StructureImports structure;

	private final PeopleImports people;

	private final SchoolLookups lookups;

	ClassHandler(StructureImports structure, PeopleImports people, SchoolLookups lookups) {
		this.structure = structure;
		this.people = people;
		this.lookups = lookups;
	}

	@Override
	public ImportType type() {
		return ImportType.CLASSES;
	}

	@Override
	public Session open(JobContext context) {
		return new ClassSession(context, this.lookups.load(context.schoolId()));
	}

	static String classType(String raw) {
		return switch (Cells.key(raw)) {
			case "homeroom", "home", "መደበኛ" -> "HOMEROOM";
			case "subject", "የትምህርትዓይነት" -> "SUBJECT";
			case "club", "ክለብ" -> "CLUB";
			case "other", "ሌላ" -> "OTHER";
			default -> null;
		};
	}

	private final class ClassSession implements Session {

		private final JobContext ctx;

		private final Loaded school;

		private final Map<String, Integer> seen = new HashMap<>();

		ClassSession(JobContext ctx, Loaded school) {
			this.ctx = ctx;
			this.school = school;
		}

		@Override
		public Planned plan(RowInput in) {
			Draft d = new Draft(in.sourceWarnings());
			String name = bounded(d, "class_name", Cells.text(in.get("class_name")), 100);
			if (in.get("class_name") == null) {
				d.error("class_name", "required");
			}
			d.put("className", name);
			YearInfo year = null;
			if (in.get("year") != null) {
				year = this.school.year(in.get("year")).orElse(null);
				if (year == null) {
					d.error("year", "year_not_found", in.get("year"));
				}
			}
			else {
				year = this.school.currentYear().orElse(null);
				if (year == null) {
					d.error("year", "no_current_year");
				}
			}
			if (year != null && "CLOSED".equals(year.status())) {
				d.error("year", "year_closed", year.name());
			}
			if (year != null) {
				d.put("yearId", year.id().toString());
				d.put("yearName", year.name());
			}
			GradeInfo grade = null;
			if (in.get("grade_level") != null) {
				grade = this.school.grade(in.get("grade_level")).orElse(null);
				if (grade == null) {
					d.error("grade_level", "grade_not_found", in.get("grade_level"));
				}
				else {
					d.put("gradeLevelId", grade.id().toString());
					d.put("gradeLevelName", grade.name());
				}
			}
			UUID campusId = null;
			if (in.get("campus") != null) {
				Optional<CampusEntry> campus = this.school.campus(in.get("campus"));
				if (campus.isPresent()) {
					campusId = campus.get().id();
					d.put("campusId", campusId.toString());
					d.put("campusName", campus.get().name());
				}
				else {
					d.error("campus", "campus_not_found", in.get("campus"));
				}
			}
			String type = null;
			if (in.get("class_type") != null) {
				type = classType(in.get("class_type"));
				if (type == null) {
					d.error("class_type", "class_type_invalid", in.get("class_type"));
				}
			}
			d.put("classType", type);
			UUID leadId = leadTeacher(d, in);
			if (name != null && year != null) {
				String key = Cells.key(name) + "|" + year.id();
				Integer first = this.seen.putIfAbsent(key, in.rowNumber());
				if (first != null) {
					d.error("class_name", "duplicate_in_file", String.valueOf(first));
				}
			}
			if (!d.ok()) {
				return d.invalid();
			}
			d.entityType = "CLASS";
			ClassInfo existing = this.school.classes.stream()
				.filter((c) -> Cells.key(c.name()).equals(Cells.key(name)) && c.academicYearId().equals(d.normalized.get("yearId") == null ? null : UUID.fromString(d.normalized.get("yearId").toString())))
				.filter((c) -> "ACTIVE".equals(c.status()))
				.findFirst()
				.orElse(null);
			if (existing == null) {
				boolean archived = this.school.classes.stream()
					.anyMatch((c) -> Cells.key(c.name()).equals(Cells.key(name)) && "ARCHIVED".equals(c.status())
							&& c.academicYearId().toString().equals(d.normalized.get("yearId")));
				if (archived) {
					d.error("class_name", "class_archived", name);
					return d.invalid();
				}
				return d.valid(Action.CREATE);
			}
			d.put("matchedId", existing.id().toString());
			d.entityId = existing.id();
			List<String> changes = new ArrayList<>();
			if (grade != null && !grade.id().equals(existing.gradeLevelId())) {
				changes.add("grade_level");
			}
			if (campusId != null && !campusId.equals(existing.campusId())) {
				changes.add("campus");
			}
			if (type != null && !type.equals(existing.classType())) {
				changes.add("class_type");
			}
			if (leadId != null) {
				if (existing.leadTeacherId() == null) {
					changes.add("lead_teacher");
				}
				else if (!existing.leadTeacherId().equals(leadId)) {
					d.warn("lead_teacher_phone", "lead_differs");
				}
			}
			d.normalized.put("changes", changes);
			return d.valid(changes.isEmpty() ? Action.NONE : Action.UPDATE);
		}

		/** The lead teacher named by phone or email, when she is a teacher of this school. */
		private UUID leadTeacher(Draft d, RowInput in) {
			String phone = (in.get("lead_teacher_phone") != null) ? phone(d, "lead_teacher_phone", in.get("lead_teacher_phone"), true)
					: null;
			String email = null;
			if (in.get("lead_teacher_email") != null) {
				email = Cells.email(in.get("lead_teacher_email"));
				if (email == null) {
					d.warn("lead_teacher_email", "email_invalid");
				}
			}
			if (phone == null && email == null) {
				return null;
			}
			List<StaffSnapshot> found = ClassHandler.this.people.staffByContact(this.ctx.schoolId(), phone, email);
			StaffSnapshot teacher = found.stream().filter((s) -> "TEACHER".equals(s.staffType())).findFirst().orElse(null);
			if (found.size() > 1 || teacher == null) {
				d.warn("lead_teacher_phone", found.isEmpty() ? "lead_teacher_not_found" : "lead_not_teacher");
				return null;
			}
			d.put("leadTeacherId", teacher.userId().toString());
			return teacher.userId();
		}

		@Override
		public Applied apply(Row row) {
			Map<String, Object> n = row.normalized();
			UUID schoolId = this.ctx.schoolId();
			UUID lead = uuid(n, "leadTeacherId");
			if (row.action() == Action.CREATE) {
				UUID id = ClassHandler.this.structure.createClass(schoolId, this.ctx.actorId(),
						new NewClass(str(n, "className"), uuid(n, "yearId"), uuid(n, "gradeLevelId"), uuid(n, "campusId"),
								str(n, "classType"), lead));
				return new Applied("CREATED", "CLASS", id, null);
			}
			UUID id = uuid(n, "matchedId");
			if (id == null) {
				throw ApiException.conflict("import.target_missing");
			}
			ClassInfo current = ClassHandler.this.structure.classes(schoolId)
				.stream()
				.filter((c) -> c.id().equals(id))
				.findFirst()
				.orElseThrow(() -> ApiException.conflict("import.target_missing"));
			UUID grade = uuid(n, "gradeLevelId");
			UUID campus = uuid(n, "campusId");
			String type = str(n, "classType");
			boolean changed = (grade != null && !grade.equals(current.gradeLevelId()))
					|| (campus != null && !campus.equals(current.campusId()))
					|| (type != null && !type.equals(current.classType())) || (lead != null && current.leadTeacherId() == null);
			if (!changed) {
				return new Applied("UNCHANGED", "CLASS", id, null);
			}
			ClassHandler.this.structure.updateClass(schoolId, this.ctx.actorId(), id, grade, campus, type, lead);
			return new Applied("UPDATED", "CLASS", id, null);
		}

	}

}
