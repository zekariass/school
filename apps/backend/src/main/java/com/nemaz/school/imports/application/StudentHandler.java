package com.nemaz.school.imports.application;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.ClassMemberships.StudentClass;
import com.nemaz.school.academic.StructureImports.YearInfo;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.text.PersonNames;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.SchoolLookups.ClassMatch;
import com.nemaz.school.imports.application.SchoolLookups.Loaded;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.DateParser;
import com.nemaz.school.imports.domain.DateParser.DateSystem;
import com.nemaz.school.imports.domain.EthiopianCalendar;
import com.nemaz.school.imports.domain.Genders;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.people.PeopleImports.NewStudent;
import com.nemaz.school.people.PeopleImports.StudentCandidate;
import com.nemaz.school.people.PeopleImports.StudentChange;
import com.nemaz.school.people.PeopleImports.StudentSnapshot;
import com.nemaz.school.tenancy.TenantDirectory.CampusEntry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import static com.nemaz.school.imports.application.HandlerSupport.bounded;
import static com.nemaz.school.imports.application.HandlerSupport.phone;
import static com.nemaz.school.imports.application.HandlerSupport.str;
import static com.nemaz.school.imports.application.HandlerSupport.uuid;

/**
 * Students: matched to existing ones by external id, then student number, then (name, birth date) with the trigram
 * key; created otherwise. A student is a person: a close name with the same birth date and a shared class or
 * guardian phone is the same student, a close name alone is a possible duplicate for the administrator to decide.
 * Guardian columns stage contacts only ({@code guardian_contact_candidates}); nothing here creates a guardian
 * account or link (R-29).
 */
@Component
class StudentHandler implements RowHandler {

	private static final int MAX_NAME = 100;

	private final PeopleImports people;

	private final ClassMemberships memberships;

	private final SchoolLookups lookups;

	private final ExternalMappings mappings;

	private final ImportProperties properties;

	private final JdbcClient jdbc;

	StudentHandler(PeopleImports people, ClassMemberships memberships, SchoolLookups lookups, ExternalMappings mappings,
			ImportProperties properties, JdbcClient jdbc) {
		this.people = people;
		this.memberships = memberships;
		this.lookups = lookups;
		this.mappings = mappings;
		this.properties = properties;
		this.jdbc = jdbc;
	}

	@Override
	public ImportType type() {
		return ImportType.STUDENTS;
	}

	@Override
	public Session open(JobContext context) {
		return new StudentSession(context, this.lookups.load(context.schoolId()));
	}

	private final class StudentSession implements Session {

		private final JobContext ctx;

		private final Loaded school;

		private final Map<String, Integer> numbers = new HashMap<>();

		private final Map<String, Integer> externals = new HashMap<>();

		private final Map<String, Integer> usernames = new HashMap<>();

		private final Map<String, Integer> identities = new HashMap<>();

		/** Students the school's plan still allows (null: unlimited); counts the rows planned to be created. */
		private Long capacity;

		StudentSession(JobContext ctx, Loaded school) {
			this.ctx = ctx;
			this.school = school;
			java.util.OptionalLong remaining = StudentHandler.this.people.remainingStudentCapacity(ctx.schoolId());
			this.capacity = remaining.isPresent() ? remaining.getAsLong() : null;
		}

		@Override
		public Planned plan(RowInput in) {
			Draft d = new Draft(in.sourceWarnings());
			String given = bounded(d, "given_name", PersonNames.clean(in.get("given_name")), MAX_NAME);
			String father = bounded(d, "father_name", PersonNames.clean(in.get("father_name")), MAX_NAME);
			String grand = bounded(d, "grandfather_name", PersonNames.clean(in.get("grandfather_name")), MAX_NAME);
			if (given == null && in.get("given_name") == null) {
				d.error("given_name", "required");
			}
			else if (given == null && d.errors.stream().noneMatch((p) -> "given_name".equals(p.column()))) {
				d.error("given_name", "name_invalid");
			}
			if (father == null && in.get("father_name") == null) {
				d.error("father_name", "required");
			}
			else if (father == null && d.errors.stream().noneMatch((p) -> "father_name".equals(p.column()))) {
				d.error("father_name", "name_invalid");
			}
			for (String[] part : new String[][] { { "given_name", given }, { "father_name", father },
					{ "grandfather_name", grand } }) {
				if (part[1] != null && part[1].chars().anyMatch(Character::isDigit)) {
					d.warn(part[0], "name_has_digits");
				}
			}
			d.put("givenName", given);
			d.put("middleName", father);
			d.put("familyName", grand);

			LocalDate dob = null;
			if (in.get("date_of_birth") == null) {
				d.error("date_of_birth", "required");
			}
			else {
				boolean nativeDate = in.nativeDateFields().contains("date_of_birth");
				dob = HandlerSupport.date(d, "date_of_birth", in.get("date_of_birth"), nativeDate, this.ctx.dateOptions());
				if (dob != null) {
					checkAge(d, dob, in.get("date_of_birth"), nativeDate);
					d.put("dateOfBirth", dob.toString());
				}
			}
			String gender = null;
			if (in.get("gender") != null) {
				gender = Genders.parse(in.get("gender"));
				if (gender == null) {
					d.error("gender", "gender_invalid");
				}
			}
			d.put("gender", gender);
			String number = bounded(d, "student_number", Cells.identifier(in.get("student_number")), 64);
			String external = bounded(d, "external_id", Cells.identifier(in.get("external_id")), 128);
			String username = bounded(d, "username", Cells.text(in.get("username")), 64);
			if (username != null && !username.matches("^[A-Za-z][A-Za-z0-9._-]{2,30}$")) {
				d.error("username", "username_invalid");
				username = null;
			}
			d.put("studentNumber", number);
			d.put("externalId", external);
			d.put("username", username);
			duplicateInFile(d, "student_number", number, this.numbers, in.rowNumber());
			duplicateInFile(d, "external_id", external, this.externals, in.rowNumber());
			duplicateInFile(d, "username", (username != null) ? username.toLowerCase(Locale.ROOT) : null, this.usernames,
					in.rowNumber());

			UUID classId = null;
			if (in.get("class") != null) {
				YearInfo year = this.school.currentYear().orElse(null);
				ClassMatch match = this.school.classByName(in.get("class"), (year != null) ? year.id() : null);
				if (match.found() != null) {
					classId = match.found().id();
					d.put("classId", classId.toString());
					d.put("className", match.found().name());
				}
				else {
					d.error("class", match.ambiguous() ? "class_ambiguous" : (match.archivedOnly() ? "class_archived" : "class_not_found"),
							in.get("class"));
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
			List<Map<String, Object>> guardians = guardians(d, in);
			if (!guardians.isEmpty()) {
				d.normalized.put("guardians", guardians);
			}
			if (d.ok() && given != null && father != null && dob != null) {
				String identity = PersonNames.fold(given, father, grand) + "|" + dob;
				Integer first = this.identities.putIfAbsent(identity, in.rowNumber());
				if (first != null) {
					d.warn("given_name", "possible_duplicate_in_file", String.valueOf(first));
				}
			}
			if (!d.ok()) {
				return d.invalid();
			}
			return match(d, in, given, father, grand, dob, number, external, classId, guardians);
		}

		private Planned match(Draft d, RowInput in, String given, String father, String grand, LocalDate dob, String number,
				String external, UUID classId, List<Map<String, Object>> guardians) {
			UUID schoolId = this.ctx.schoolId();
			StudentSnapshot matched = null;
			String matchType = null;
			StudentSnapshot byExternal = null;
			if (external != null) {
				UUID mapped = StudentHandler.this.mappings.find(this.ctx.externalSystemId(), "STUDENT", external).orElse(null);
				if (mapped != null) {
					byExternal = StudentHandler.this.people.student(schoolId, mapped).orElse(null);
				}
			}
			StudentSnapshot byNumber = (number != null) ? StudentHandler.this.people.studentByNumber(schoolId, number).orElse(null)
					: null;
			if (byExternal != null && !byExternal.live()) {
				d.error("external_id", "student_not_active");
				return d.invalid();
			}
			if (byExternal != null && byNumber != null && !byExternal.studentId().equals(byNumber.studentId())) {
				d.error("student_number", "identifier_conflict");
				return d.invalid();
			}
			if (byExternal != null) {
				matched = byExternal;
				matchType = "EXTERNAL_ID";
			}
			else if (byNumber != null) {
				if (!byNumber.live()) {
					d.error("student_number", "student_number_inactive");
					return d.invalid();
				}
				matched = byNumber;
				matchType = "STUDENT_NUMBER";
			}
			if (matched == null) {
				String fold = PersonNames.fold(given, father, grand);
				List<StudentCandidate> candidates = StudentHandler.this.people.studentCandidates(schoolId, fold, dob,
						StudentHandler.this.properties.possibleDuplicateSimilarity(), 5)
					.stream()
					.filter((c) -> number == null || c.studentNumber() == null || number.equals(c.studentNumber()))
					.toList();
				if (!candidates.isEmpty()) {
					Map<UUID, List<StudentClass>> classes = StudentHandler.this.memberships
						.activeClassesOf(candidates.stream().map(StudentCandidate::studentId).toList());
					StudentCandidate auto = null;
					for (StudentCandidate c : candidates) {
						if (c.similarity() >= StudentHandler.this.properties.autoMatchSimilarity() && dob.equals(c.dateOfBirth())
								&& corroborated(c, classes.getOrDefault(c.studentId(), List.of()), classId, guardians)) {
							auto = c;
							break;
						}
					}
					if (auto != null) {
						matched = StudentHandler.this.people.student(schoolId, auto.studentId()).orElse(null);
						matchType = "NAME_DOB";
						d.warn("given_name", "matched_by_name", auto.displayName());
					}
					else {
						for (StudentCandidate c : candidates) {
							Map<String, Object> view = new LinkedHashMap<>();
							view.put("studentId", c.studentId().toString());
							view.put("displayName", c.displayName());
							view.put("dateOfBirth", String.valueOf(c.dateOfBirth()));
							view.put("studentNumber", c.studentNumber());
							view.put("similarity", Math.round(c.similarity() * 100.0) / 100.0);
							view.put("birthDate", dob.equals(c.dateOfBirth()) ? "SAME" : "WITHIN_A_YEAR");
							view.put("classes", classes.getOrDefault(c.studentId(), List.of()).stream().map(StudentClass::name).toList());
							d.candidates.add(view);
						}
						return d.duplicate();
					}
				}
			}
			if (matched == null) {
				return create(d);
			}
			d.put("matchedId", matched.studentId().toString());
			d.put("matchType", matchType);
			d.entityType = "STUDENT";
			d.entityId = matched.studentId();
			List<String> changes = changes(matched, d.normalized, classId);
			d.normalized.put("changes", changes);
			return d.valid(changes.isEmpty() ? Action.NONE : Action.UPDATE);
		}

		/** A new student, unless the plan's student limit would be passed (the commit would fail at that row). */
		private Planned create(Draft d) {
			if (this.capacity != null) {
				if (this.capacity <= 0) {
					d.error(null, "student_limit_reached");
					return d.invalid();
				}
				this.capacity--;
			}
			d.entityType = "STUDENT";
			return d.valid(Action.CREATE);
		}

		private boolean corroborated(StudentCandidate c, List<StudentClass> classes, UUID classId,
				List<Map<String, Object>> guardians) {
			if (classId != null && classes.stream().anyMatch((k) -> k.classId().equals(classId))) {
				return true;
			}
			List<String> phones = guardians.stream().map((g) -> str(g, "phone")).filter(Objects::nonNull).toList();
			if (phones.isEmpty()) {
				return false;
			}
			return StudentHandler.this.jdbc.sql("""
					select exists (select 1 from guardian_contact_candidates where student_id = :st and phone_e164 in (:phones)
					               and status in ('PENDING', 'INVITED'))""")
				.param("st", c.studentId())
				.param("phones", phones)
				.query(Boolean.class)
				.single();
		}

		private void checkAge(Draft d, LocalDate dob, String raw, boolean nativeDate) {
			if (dob.isAfter(this.ctx.today())) {
				d.error("date_of_birth", "date_future");
				return;
			}
			int age = Period.between(dob, this.ctx.today()).getYears();
			if (age >= 3 && age <= 25) {
				return;
			}
			d.warn("date_of_birth", "age_implausible", String.valueOf(age));
			if (nativeDate || raw.matches("^\\s*\\d{5,6}(\\.\\d+)?\\s*$")) {
				return;
			}
			// The same digits in the other calendar may be a believable age: say so.
			try {
				LocalDate other;
				if (this.ctx.dateOptions().system() == DateSystem.ETHIOPIAN) {
					EthiopianCalendar.Civil typed = EthiopianCalendar.toEthiopian(dob);
					other = LocalDate.of(typed.year(), typed.month(), typed.day());
				}
				else if (EthiopianCalendar.isValid(dob.getYear(), dob.getMonthValue(), dob.getDayOfMonth())) {
					other = EthiopianCalendar.toGregorian(dob.getYear(), dob.getMonthValue(), dob.getDayOfMonth());
				}
				else {
					return;
				}
				int otherAge = Period.between(other, this.ctx.today()).getYears();
				if (!other.isAfter(this.ctx.today()) && otherAge >= 3 && otherAge <= 25) {
					d.warn("date_of_birth", "date_maybe_other_calendar");
				}
			}
			catch (java.time.DateTimeException | IllegalArgumentException ex) {
				// not a date in the other calendar either
			}
		}

		private void duplicateInFile(Draft d, String column, String value, Map<String, Integer> seen, int row) {
			if (value == null) {
				return;
			}
			Integer first = seen.putIfAbsent(value, row);
			if (first != null) {
				d.error(column, "duplicate_in_file", String.valueOf(first));
			}
		}

		private List<Map<String, Object>> guardians(Draft d, RowInput in) {
			List<Map<String, Object>> result = new ArrayList<>();
			for (int slot = 1; slot <= 2; slot++) {
				String prefix = "guardian" + slot + "_";
				String rawName = in.get(prefix + "name");
				String rawPhone = in.get(prefix + "phone");
				String rawEmail = in.get(prefix + "email");
				String rawRelation = in.get(prefix + "relationship");
				if (rawName == null && rawPhone == null && rawEmail == null && rawRelation == null) {
					continue;
				}
				String phone = (rawPhone != null) ? phone(d, prefix + "phone", rawPhone, true) : null;
				String email = null;
				if (rawEmail != null) {
					email = Cells.email(rawEmail);
					if (email == null) {
						d.warn(prefix + "email", "email_invalid");
					}
				}
				if (phone == null && email == null) {
					d.warn(prefix + "name", "guardian_contact_missing");
					continue;
				}
				String name = PersonNames.clean(rawName);
				if (name != null && name.length() > 200) {
					name = name.substring(0, 200);
				}
				Map<String, Object> g = new LinkedHashMap<>();
				g.put("slot", slot);
				g.put("name", name);
				g.put("phone", phone);
				g.put("email", email);
				g.put("relationship", relationship(d, prefix + "relationship", rawRelation));
				result.add(g);
			}
			return result;
		}

		private String relationship(Draft d, String column, String raw) {
			if (raw == null) {
				return "OTHER";
			}
			String key = Cells.key(raw);
			String value = switch (key) {
				case "mother", "mom", "mum", "እናት" -> "MOTHER";
				case "father", "dad", "አባት" -> "FATHER";
				case "grandparent", "grandmother", "grandfather", "አያት" -> "GRANDPARENT";
				case "sibling", "brother", "sister", "ወንድም", "እህት" -> "SIBLING";
				case "aunt", "uncle", "auntuncle", "አክስት", "አጎት" -> "AUNT_UNCLE";
				case "guardian", "legalguardian", "ሞግዚት" -> "LEGAL_GUARDIAN";
				case "other" -> "OTHER";
				default -> null;
			};
			if (value == null) {
				d.warn(column, "relationship_unrecognized", raw);
				return "OTHER";
			}
			return value;
		}

		private List<String> changes(StudentSnapshot s, Map<String, Object> n, UUID classId) {
			List<String> changes = new ArrayList<>();
			differs(changes, "given_name", str(n, "givenName"), s.givenName());
			differs(changes, "father_name", str(n, "middleName"), s.middleName());
			differs(changes, "grandfather_name", str(n, "familyName"), s.familyName());
			differs(changes, "date_of_birth", str(n, "dateOfBirth"), (s.dateOfBirth() != null) ? s.dateOfBirth().toString() : null);
			differs(changes, "gender", str(n, "gender"), s.gender());
			differs(changes, "student_number", str(n, "studentNumber"), s.studentNumber());
			differs(changes, "campus", str(n, "campusId"), (s.campusId() != null) ? s.campusId().toString() : null);
			String username = str(n, "username");
			if (username != null && !username.equalsIgnoreCase(s.username())) {
				changes.add("username");
			}
			if (classId != null && !StudentHandler.this.memberships.activeStudentIds(classId).contains(s.studentId())) {
				changes.add("class");
			}
			return changes;
		}

		private void differs(List<String> changes, String field, String wanted, String current) {
			if (wanted != null && !wanted.equals(current)) {
				changes.add(field);
			}
		}

		@Override
		public Applied apply(Row row) {
			Map<String, Object> n = row.normalized();
			UUID schoolId = this.ctx.schoolId();
			UUID actor = this.ctx.actorId();
			String external = str(n, "externalId");
			UUID classId = uuid(n, "classId");
			if (row.action() == Action.CREATE) {
				UUID id = StudentHandler.this.people.createStudent(schoolId, actor,
						new NewStudent(str(n, "givenName"), str(n, "middleName"), str(n, "familyName"),
								HandlerSupport.date(n, "dateOfBirth"), str(n, "gender"), str(n, "studentNumber"),
								uuid(n, "campusId"), str(n, "username"), classId, external));
				stageGuardians(schoolId, id, n);
				return new Applied("CREATED", "STUDENT", id, external);
			}
			UUID id = (row.resolvedEntityId() != null) ? row.resolvedEntityId() : uuid(n, "matchedId");
			if (id == null) {
				throw ApiException.conflict("import.target_missing");
			}
			StudentSnapshot snapshot = StudentHandler.this.people.student(schoolId, id)
				.filter(StudentSnapshot::live)
				.orElseThrow(() -> ApiException.conflict("import.target_missing"));
			List<String> fields = changes(snapshot, n, null);
			if (!fields.isEmpty()) {
				StudentChange change = new StudentChange(fields.contains("given_name") ? str(n, "givenName") : null,
						fields.contains("father_name") ? str(n, "middleName") : null,
						fields.contains("grandfather_name") ? str(n, "familyName") : null,
						fields.contains("date_of_birth") ? HandlerSupport.date(n, "dateOfBirth") : null,
						fields.contains("gender") ? str(n, "gender") : null,
						fields.contains("student_number") ? str(n, "studentNumber") : null,
						fields.contains("campus") ? uuid(n, "campusId") : null,
						fields.contains("username") ? str(n, "username") : null);
				StudentHandler.this.people.updateStudent(schoolId, actor, id, change, external);
			}
			boolean joined = false;
			if (classId != null && !StudentHandler.this.memberships.activeStudentIds(classId).contains(id)) {
				StudentHandler.this.memberships.addStudent(schoolId, classId, id, actor);
				joined = true;
			}
			stageGuardians(schoolId, id, n);
			return new Applied((!fields.isEmpty() || joined) ? "UPDATED" : "UNCHANGED", "STUDENT", id, external);
		}

		@SuppressWarnings("unchecked")
		private void stageGuardians(UUID schoolId, UUID studentId, Map<String, Object> n) {
			Object list = n.get("guardians");
			if (!(list instanceof List<?> guardians)) {
				return;
			}
			for (Object o : guardians) {
				Map<String, Object> g = (Map<String, Object>) o;
				StudentHandler.this.jdbc.sql("""
						insert into guardian_contact_candidates (id, school_id, student_id, import_job_id, relationship_type, name,
						                                        phone_e164, email)
						values (:id, :s, :st, :job, :rel, :name, :phone, :email)
						on conflict do nothing""")
					.param("id", Ids.newId())
					.param("s", schoolId)
					.param("st", studentId)
					.param("job", this.ctx.jobId())
					.param("rel", str(g, "relationship"))
					.param("name", str(g, "name"))
					.param("phone", str(g, "phone"))
					.param("email", str(g, "email"))
					.update();
			}
		}

	}

}
