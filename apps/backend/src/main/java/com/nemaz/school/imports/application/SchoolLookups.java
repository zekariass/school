package com.nemaz.school.imports.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.StructureImports;
import com.nemaz.school.academic.StructureImports.ClassInfo;
import com.nemaz.school.academic.StructureImports.GradeInfo;
import com.nemaz.school.academic.StructureImports.SubjectInfo;
import com.nemaz.school.academic.StructureImports.YearInfo;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.tenancy.TenantDirectory;
import com.nemaz.school.tenancy.TenantDirectory.CampusEntry;

import org.springframework.stereotype.Component;

/** The school's structure as lookup tables for one import run: years, grade levels, subjects, classes, campuses. */
@Component
class SchoolLookups {

	private final StructureImports structure;

	private final TenantDirectory directory;

	SchoolLookups(StructureImports structure, TenantDirectory directory) {
		this.structure = structure;
		this.directory = directory;
	}

	Loaded load(UUID schoolId) {
		return new Loaded(this.structure.years(schoolId), this.structure.gradeLevels(schoolId),
				this.structure.subjects(schoolId), this.structure.classes(schoolId), this.directory.campusesOf(schoolId));
	}

	/** Result of looking a class up by name. */
	record ClassMatch(ClassInfo found, boolean ambiguous, boolean archivedOnly) {
	}

	static final class Loaded {

		final List<YearInfo> years;

		final List<GradeInfo> grades;

		final List<SubjectInfo> subjects;

		final List<ClassInfo> classes;

		final List<CampusEntry> campuses;

		private final Map<String, GradeInfo> gradeByKey = new HashMap<>();

		private final Map<String, SubjectInfo> subjectByCode = new HashMap<>();

		private final Map<String, SubjectInfo> subjectByName = new HashMap<>();

		Loaded(List<YearInfo> years, List<GradeInfo> grades, List<SubjectInfo> subjects, List<ClassInfo> classes,
				List<CampusEntry> campuses) {
			this.years = years;
			this.grades = grades;
			this.subjects = subjects;
			this.classes = classes;
			this.campuses = campuses;
			for (GradeInfo g : grades) {
				this.gradeByKey.putIfAbsent("n:" + Cells.key(g.name()), g);
				if (g.code() != null) {
					this.gradeByKey.putIfAbsent("c:" + Cells.key(g.code()), g);
				}
			}
			for (SubjectInfo s : subjects) {
				this.subjectByName.putIfAbsent(Cells.key(s.name()), s);
				if (s.code() != null) {
					this.subjectByCode.putIfAbsent(Cells.key(s.code()), s);
				}
			}
		}

		Optional<YearInfo> currentYear() {
			return this.years.stream().filter(YearInfo::current).findFirst();
		}

		Optional<YearInfo> year(String name) {
			String key = Cells.key(name);
			return this.years.stream().filter((y) -> Cells.key(y.name()).equals(key)).findFirst();
		}

		/** By name first, then by code ("Grade 8" or "8"). */
		Optional<GradeInfo> grade(String nameOrCode) {
			String key = Cells.key(nameOrCode);
			GradeInfo byName = this.gradeByKey.get("n:" + key);
			return Optional.ofNullable((byName != null) ? byName : this.gradeByKey.get("c:" + key));
		}

		Optional<GradeInfo> gradeByCode(String code) {
			return Optional.ofNullable(this.gradeByKey.get("c:" + Cells.key(code)));
		}

		Optional<GradeInfo> gradeByName(String name) {
			return Optional.ofNullable(this.gradeByKey.get("n:" + Cells.key(name)));
		}

		Optional<SubjectInfo> subjectByCode(String code) {
			return Optional.ofNullable(this.subjectByCode.get(Cells.key(code)));
		}

		Optional<SubjectInfo> subjectByName(String name) {
			return Optional.ofNullable(this.subjectByName.get(Cells.key(name)));
		}

		/** Name or short code of an ACTIVE campus. */
		Optional<CampusEntry> campus(String nameOrCode) {
			String key = Cells.key(nameOrCode);
			return this.campuses.stream()
				.filter((c) -> "ACTIVE".equals(c.status()))
				.filter((c) -> Cells.key(c.name()).equals(key) || (c.code() != null && Cells.key(c.code()).equals(key)))
				.findFirst();
		}

		/**
		 * A class by name in the given academic year: the live ones first; several live ones with the name
		 * (different campuses) are ambiguous; only an archived one is reported as such.
		 */
		ClassMatch classByName(String name, UUID yearId) {
			String key = Cells.key(name);
			List<ClassInfo> named = this.classes.stream()
				.filter((c) -> Cells.key(c.name()).equals(key) && (yearId == null || c.academicYearId().equals(yearId)))
				.toList();
			List<ClassInfo> live = named.stream().filter((c) -> "ACTIVE".equals(c.status())).toList();
			if (live.size() == 1) {
				return new ClassMatch(live.getFirst(), false, false);
			}
			if (live.size() > 1) {
				return new ClassMatch(null, true, false);
			}
			return new ClassMatch(null, false, !named.isEmpty());
		}

	}

}
