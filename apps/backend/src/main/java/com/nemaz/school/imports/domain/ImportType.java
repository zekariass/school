package com.nemaz.school.imports.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a file contains and which columns it may have. Field keys are the machine names used in templates and in
 * the API; headers in other wordings or in Amharic are matched to them through {@link HeaderSynonyms}.
 * <p>
 * A field is {@code required}, optional, or part of a {@code oneOf} group of which at least one must be mapped
 * (a staff member needs a phone or an email). Academic years and periods are not imported: a school has a handful
 * and sets them up on the academic year screen.
 */
public enum ImportType {

	GRADE_LEVELS(field("name", true), field("code"), field("sequence")),

	SUBJECTS(field("name", true), field("code"), field("color")),

	CLASSES(field("class_name", true), field("grade_level"), field("year"), field("campus"), field("class_type"),
			field("lead_teacher_phone"), field("lead_teacher_email")),

	STAFF(field("given_name", true), field("father_name", true), field("grandfather_name"), oneOf("phone", "contact"),
			oneOf("email", "contact"), field("staff_type", true), field("job_title"), field("campus"),
			field("external_id")),

	STUDENTS(field("given_name", true), field("father_name", true), field("grandfather_name"),
			field("date_of_birth", true), field("gender"), field("student_number"), field("external_id"), field("class"),
			field("campus"), field("username"), field("guardian1_name"), field("guardian1_phone"),
			field("guardian1_email"), field("guardian1_relationship"), field("guardian2_name"), field("guardian2_phone"),
			field("guardian2_email"), field("guardian2_relationship")),

	ENROLLMENTS(oneOf("student_number", "student"), oneOf("external_id", "student"), field("class_name", true));

	private final List<Field> fields;

	ImportType(Field... fields) {
		this.fields = List.of(fields);
	}

	public List<Field> fields() {
		return this.fields;
	}

	public boolean has(String key) {
		return this.fields.stream().anyMatch((f) -> f.key().equals(key));
	}

	/**
	 * What still has to be mapped: every missing required field, and for each group without any mapped field the
	 * group's fields joined by {@code |} (for example {@code phone|email}).
	 */
	public List<String> missing(Set<String> mapped) {
		List<String> missing = new ArrayList<>();
		Set<String> groups = new LinkedHashSet<>();
		for (Field f : this.fields) {
			if (f.required() && !mapped.contains(f.key())) {
				missing.add(f.key());
			}
			if (f.oneOf() != null) {
				groups.add(f.oneOf());
			}
		}
		for (String group : groups) {
			List<String> members = this.fields.stream().filter((f) -> group.equals(f.oneOf())).map(Field::key).toList();
			if (members.stream().noneMatch(mapped::contains)) {
				missing.add(String.join("|", members));
			}
		}
		return missing;
	}

	/** The fields a client must have mapped (required ones, and one group per {@code |} entry). */
	public List<String> requiredFields() {
		return missing(Set.of());
	}

	public record Field(String key, boolean required, String oneOf) {
	}

	private static Field field(String key) {
		return new Field(key, false, null);
	}

	private static Field field(String key, boolean required) {
		return new Field(key, required, null);
	}

	private static Field oneOf(String key, String group) {
		return new Field(key, false, group);
	}

}
