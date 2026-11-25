package com.nemaz.school.imports.application;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.academic.StructureImports;
import com.nemaz.school.academic.StructureImports.SubjectInfo;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.SchoolLookups.Loaded;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.ImportType;

import org.springframework.stereotype.Component;

import static com.nemaz.school.imports.application.HandlerSupport.bounded;
import static com.nemaz.school.imports.application.HandlerSupport.str;
import static com.nemaz.school.imports.application.HandlerSupport.uuid;

/** Subjects: matched by code, else by name; the name, code and color of the file are applied to an existing subject. */
@Component
class SubjectHandler implements RowHandler {

	private final StructureImports structure;

	private final SchoolLookups lookups;

	SubjectHandler(StructureImports structure, SchoolLookups lookups) {
		this.structure = structure;
		this.lookups = lookups;
	}

	@Override
	public ImportType type() {
		return ImportType.SUBJECTS;
	}

	@Override
	public Session open(JobContext context) {
		return new SubjectSession(context, this.lookups.load(context.schoolId()));
	}

	private final class SubjectSession implements Session {

		private final JobContext ctx;

		private final Loaded school;

		private final Map<String, Integer> names = new HashMap<>();

		private final Map<String, Integer> codes = new HashMap<>();

		SubjectSession(JobContext ctx, Loaded school) {
			this.ctx = ctx;
			this.school = school;
		}

		@Override
		public Planned plan(RowInput in) {
			Draft d = new Draft(in.sourceWarnings());
			String name = bounded(d, "name", Cells.text(in.get("name")), 100);
			if (in.get("name") == null) {
				d.error("name", "required");
			}
			String code = bounded(d, "code", Cells.identifier(in.get("code")), 32);
			String color = Cells.text(in.get("color"));
			if (color != null) {
				if (color.matches("^#?[0-9a-fA-F]{6}$")) {
					color = ((color.startsWith("#")) ? color : "#" + color).toLowerCase(Locale.ROOT);
				}
				else {
					d.error("color", "color_invalid", color);
					color = null;
				}
			}
			d.put("name", name);
			d.put("code", code);
			d.put("color", color);
			if (name != null) {
				Integer first = this.names.putIfAbsent(Cells.key(name), in.rowNumber());
				if (first != null) {
					d.error("name", "duplicate_in_file", String.valueOf(first));
				}
			}
			if (code != null) {
				Integer first = this.codes.putIfAbsent(Cells.key(code), in.rowNumber());
				if (first != null) {
					d.error("code", "duplicate_in_file", String.valueOf(first));
				}
			}
			if (!d.ok()) {
				return d.invalid();
			}
			SubjectInfo byCode = (code != null) ? this.school.subjectByCode(code).orElse(null) : null;
			SubjectInfo byName = this.school.subjectByName(name).orElse(null);
			if (byCode != null && byName != null && !byCode.id().equals(byName.id())) {
				d.error("code", "identifier_conflict");
				return d.invalid();
			}
			SubjectInfo matched = (byCode != null) ? byCode : byName;
			d.entityType = "SUBJECT";
			if (matched == null) {
				return d.valid(Action.CREATE);
			}
			if (code != null && byCode == null && this.school.subjectByCode(code).isPresent()) {
				d.error("code", "code_taken");
				return d.invalid();
			}
			d.put("matchedId", matched.id().toString());
			d.entityId = matched.id();
			boolean changed = !name.equals(matched.name()) || (code != null && !code.equalsIgnoreCase(matched.code()))
					|| (color != null && !color.equalsIgnoreCase(matched.color()));
			return d.valid(changed ? Action.UPDATE : Action.NONE);
		}

		@Override
		public Applied apply(Row row) {
			Map<String, Object> n = row.normalized();
			UUID schoolId = this.ctx.schoolId();
			if (row.action() == Action.CREATE) {
				UUID id = SubjectHandler.this.structure.createSubject(schoolId, this.ctx.actorId(), str(n, "name"),
						str(n, "code"), str(n, "color"));
				return new Applied("CREATED", "SUBJECT", id, null);
			}
			UUID id = uuid(n, "matchedId");
			if (id == null) {
				throw ApiException.conflict("import.target_missing");
			}
			SubjectInfo current = SubjectHandler.this.structure.subjects(schoolId)
				.stream()
				.filter((s) -> s.id().equals(id))
				.findFirst()
				.orElseThrow(() -> ApiException.conflict("import.target_missing"));
			String code = str(n, "code");
			String color = str(n, "color");
			boolean changed = !str(n, "name").equals(current.name()) || (code != null && !code.equalsIgnoreCase(current.code()))
					|| (color != null && !color.equalsIgnoreCase(current.color()));
			if (!changed) {
				return new Applied("UNCHANGED", "SUBJECT", id, null);
			}
			SubjectHandler.this.structure.updateSubject(schoolId, this.ctx.actorId(), id, str(n, "name"), code, color);
			return new Applied("UPDATED", "SUBJECT", id, null);
		}

	}

}
