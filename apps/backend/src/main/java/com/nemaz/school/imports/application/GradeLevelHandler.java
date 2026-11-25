package com.nemaz.school.imports.application;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.academic.StructureImports;
import com.nemaz.school.academic.StructureImports.GradeInfo;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.SchoolLookups.Loaded;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.ImportType;

import org.springframework.stereotype.Component;

import static com.nemaz.school.imports.application.HandlerSupport.bounded;
import static com.nemaz.school.imports.application.HandlerSupport.integer;
import static com.nemaz.school.imports.application.HandlerSupport.str;
import static com.nemaz.school.imports.application.HandlerSupport.uuid;

/** Grade levels: matched by code, else by name; the sequence must stay unique in the school. */
@Component
class GradeLevelHandler implements RowHandler {

	private final StructureImports structure;

	private final SchoolLookups lookups;

	GradeLevelHandler(StructureImports structure, SchoolLookups lookups) {
		this.structure = structure;
		this.lookups = lookups;
	}

	@Override
	public ImportType type() {
		return ImportType.GRADE_LEVELS;
	}

	@Override
	public Session open(JobContext context) {
		return new GradeSession(context, this.lookups.load(context.schoolId()));
	}

	private final class GradeSession implements Session {

		private final JobContext ctx;

		private final Loaded school;

		private final Map<String, Integer> names = new HashMap<>();

		private final Map<String, Integer> codes = new HashMap<>();

		private final Map<Integer, Integer> sequences = new HashMap<>();

		GradeSession(JobContext ctx, Loaded school) {
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
			Integer sequence = null;
			if (in.get("sequence") != null) {
				String text = Cells.identifier(in.get("sequence"));
				try {
					sequence = Integer.valueOf(text);
					if (sequence < 1 || sequence > 1000) {
						throw new NumberFormatException();
					}
				}
				catch (NumberFormatException ex) {
					d.error("sequence", "sequence_invalid", in.get("sequence"));
					sequence = null;
				}
			}
			d.put("name", name);
			d.put("code", code);
			d.put("sequence", sequence);
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
			if (sequence != null) {
				Integer first = this.sequences.putIfAbsent(sequence, in.rowNumber());
				if (first != null) {
					d.error("sequence", "duplicate_in_file", String.valueOf(first));
				}
			}
			if (!d.ok()) {
				return d.invalid();
			}
			GradeInfo byCode = (code != null) ? this.school.gradeByCode(code).orElse(null) : null;
			GradeInfo byName = this.school.gradeByName(name).orElse(null);
			if (byCode != null && byName != null && !byCode.id().equals(byName.id())) {
				d.error("code", "identifier_conflict");
				return d.invalid();
			}
			GradeInfo matched = (byCode != null) ? byCode : byName;
			d.entityType = "GRADE_LEVEL";
			if (sequence != null) {
				Integer wanted = sequence;
				GradeInfo holder = this.school.grades.stream().filter((g) -> g.sequence() == wanted).findFirst().orElse(null);
				if (holder != null && (matched == null || !holder.id().equals(matched.id()))) {
					d.error("sequence", "sequence_taken", String.valueOf(sequence));
					return d.invalid();
				}
			}
			if (matched == null) {
				return d.valid(Action.CREATE);
			}
			if (code != null && byCode == null && this.school.gradeByCode(code).isPresent()) {
				d.error("code", "code_taken");
				return d.invalid();
			}
			d.put("matchedId", matched.id().toString());
			d.entityId = matched.id();
			boolean changed = !name.equals(matched.name()) || (code != null && !code.equalsIgnoreCase(matched.code()))
					|| (sequence != null && sequence != matched.sequence());
			return d.valid(changed ? Action.UPDATE : Action.NONE);
		}

		@Override
		public Applied apply(Row row) {
			Map<String, Object> n = row.normalized();
			UUID schoolId = this.ctx.schoolId();
			Integer sequence = integer(n, "sequence");
			if (row.action() == Action.CREATE) {
				UUID id = GradeLevelHandler.this.structure.createGradeLevel(schoolId, this.ctx.actorId(), str(n, "name"),
						str(n, "code"), sequence);
				return new Applied("CREATED", "GRADE_LEVEL", id, null);
			}
			UUID id = uuid(n, "matchedId");
			if (id == null) {
				throw ApiException.conflict("import.target_missing");
			}
			GradeInfo current = GradeLevelHandler.this.structure.gradeLevels(schoolId)
				.stream()
				.filter((g) -> g.id().equals(id))
				.findFirst()
				.orElseThrow(() -> ApiException.conflict("import.target_missing"));
			String code = str(n, "code");
			boolean changed = !str(n, "name").equals(current.name()) || (code != null && !code.equalsIgnoreCase(current.code()))
					|| (sequence != null && sequence != current.sequence());
			if (!changed) {
				return new Applied("UNCHANGED", "GRADE_LEVEL", id, null);
			}
			GradeLevelHandler.this.structure.updateGradeLevel(schoolId, this.ctx.actorId(), id, str(n, "name"), code, sequence);
			return new Applied("UPDATED", "GRADE_LEVEL", id, null);
		}

	}

}
