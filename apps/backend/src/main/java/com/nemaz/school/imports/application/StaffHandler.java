package com.nemaz.school.imports.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.text.PersonNames;
import com.nemaz.school.imports.application.ImportModel.Action;
import com.nemaz.school.imports.application.ImportModel.Row;
import com.nemaz.school.imports.application.SchoolLookups.Loaded;
import com.nemaz.school.imports.domain.Cells;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.people.PeopleImports;
import com.nemaz.school.people.PeopleImports.NewStaff;
import com.nemaz.school.people.PeopleImports.NewStaffResult;
import com.nemaz.school.people.PeopleImports.StaffSnapshot;
import com.nemaz.school.tenancy.TenantDirectory.CampusEntry;

import org.springframework.stereotype.Component;

import static com.nemaz.school.imports.application.HandlerSupport.bounded;
import static com.nemaz.school.imports.application.HandlerSupport.phone;
import static com.nemaz.school.imports.application.HandlerSupport.str;
import static com.nemaz.school.imports.application.HandlerSupport.uuid;

/**
 * Staff: matched to an existing staff member by external id, then by normalized phone or email (the person's
 * account is reused, never duplicated); created otherwise. A file never grants an administrative role: the staff
 * type ADMINISTRATOR creates the staff record only, and the roles SCHOOL_ADMIN and CAMPUS_ADMIN are refused.
 */
@Component
class StaffHandler implements RowHandler {

	private final PeopleImports people;

	private final SchoolLookups lookups;

	private final ExternalMappings mappings;

	StaffHandler(PeopleImports people, SchoolLookups lookups, ExternalMappings mappings) {
		this.people = people;
		this.lookups = lookups;
		this.mappings = mappings;
	}

	@Override
	public ImportType type() {
		return ImportType.STAFF;
	}

	@Override
	public Session open(JobContext context) {
		return new StaffSession(context, StaffHandler.this.lookups.load(context.schoolId()));
	}

	/** The stored staff type for the words a file may use, or null. */
	static String staffType(String raw) {
		return switch (Cells.key(raw)) {
			case "teacher", "መምህር", "መምህርት" -> "TEACHER";
			case "administrator", "admin", "administration", "አስተዳዳሪ" -> "ADMINISTRATOR";
			case "supportstaff", "support", "staff", "የድጋፍሠራተኛ", "ድጋፍ" -> "SUPPORT_STAFF";
			default -> null;
		};
	}

	private final class StaffSession implements Session {

		private final JobContext ctx;

		private final Loaded school;

		private final Map<String, Integer> phones = new HashMap<>();

		private final Map<String, Integer> emails = new HashMap<>();

		private final Map<String, Integer> externals = new HashMap<>();

		StaffSession(JobContext ctx, Loaded school) {
			this.ctx = ctx;
			this.school = school;
		}

		@Override
		public Planned plan(RowInput in) {
			Draft d = new Draft(in.sourceWarnings());
			String given = bounded(d, "given_name", PersonNames.clean(in.get("given_name")), 100);
			String father = bounded(d, "father_name", PersonNames.clean(in.get("father_name")), 100);
			String grand = bounded(d, "grandfather_name", PersonNames.clean(in.get("grandfather_name")), 100);
			if (in.get("given_name") == null) {
				d.error("given_name", "required");
			}
			else if (given == null && d.ok()) {
				d.error("given_name", "name_invalid");
			}
			if (in.get("father_name") == null) {
				d.error("father_name", "required");
			}
			else if (father == null && d.ok()) {
				d.error("father_name", "name_invalid");
			}
			d.put("givenName", given);
			d.put("fatherName", father);
			d.put("grandfatherName", grand);
			String phone = (in.get("phone") != null) ? phone(this.ctx, d, "phone", in.get("phone"), false) : null;
			String email = null;
			if (in.get("email") != null) {
				email = Cells.email(in.get("email"));
				if (email == null) {
					d.error("email", "email_invalid");
				}
			}
			if (in.get("phone") == null && in.get("email") == null) {
				d.error("phone", "contact_required");
			}
			d.put("phone", phone);
			d.put("email", email);
			String type = null;
			if (in.get("staff_type") == null) {
				d.error("staff_type", "required");
			}
			else {
				String key = Cells.key(in.get("staff_type"));
				if (key.equals("schooladmin") || key.equals("campusadmin") || key.equals("schooladministrator")
						|| key.equals("campusadministrator")) {
					d.error("staff_type", "staff_type_forbidden");
				}
				else {
					type = staffType(in.get("staff_type"));
					if (type == null) {
						d.error("staff_type", "staff_type_invalid", in.get("staff_type"));
					}
				}
			}
			if ("ADMINISTRATOR".equals(type)) {
				d.warn("staff_type", "admin_role_not_granted");
			}
			d.put("staffType", type);
			String job = bounded(d, "job_title", PersonNames.clean(in.get("job_title")), 100);
			d.put("jobTitle", job);
			String external = bounded(d, "external_id", Cells.identifier(in.get("external_id")), 128);
			d.put("externalId", external);
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
			dupe(d, "phone", phone, this.phones, in.rowNumber());
			dupe(d, "email", email, this.emails, in.rowNumber());
			dupe(d, "external_id", external, this.externals, in.rowNumber());
			if (!d.ok()) {
				return d.invalid();
			}
			UUID schoolId = this.ctx.schoolId();
			StaffSnapshot byExternal = null;
			if (external != null) {
				UUID mapped = StaffHandler.this.mappings.find(this.ctx.externalSystemId(), "STAFF", external).orElse(null);
				if (mapped != null) {
					byExternal = StaffHandler.this.people.staff(schoolId, mapped).orElse(null);
				}
			}
			List<StaffSnapshot> byContact = StaffHandler.this.people.staffByContact(schoolId, phone, email);
			if (byContact.size() > 1) {
				d.error("phone", "contact_conflict");
				return d.invalid();
			}
			if (byExternal != null && !byContact.isEmpty() && !byContact.getFirst().staffId().equals(byExternal.staffId())) {
				d.error("external_id", "identifier_conflict");
				return d.invalid();
			}
			StaffSnapshot matched = byContact.isEmpty() ? byExternal : byContact.getFirst();
			if (matched == null) {
				d.entityType = "STAFF";
				return d.valid(Action.CREATE);
			}
			d.put("matchedId", matched.staffId().toString());
			d.entityType = "STAFF";
			d.entityId = matched.staffId();
			if (type != null && !type.equals(matched.staffType())) {
				d.warn("staff_type", "staff_type_differs", matched.staffType());
			}
			List<String> changes = new ArrayList<>();
			if (job != null && !job.equals(matched.jobTitle())) {
				changes.add("job_title");
			}
			if (campusId != null && !campusId.equals(matched.campusId())) {
				changes.add("campus");
			}
			if (external != null && !external.equals(matched.externalRef())) {
				changes.add("external_id");
			}
			d.normalized.put("changes", changes);
			return d.valid(changes.isEmpty() ? Action.NONE : Action.UPDATE);
		}

		private void dupe(Draft d, String column, String value, Map<String, Integer> seen, int row) {
			if (value == null) {
				return;
			}
			Integer first = seen.putIfAbsent(value, row);
			if (first != null) {
				d.error(column, "duplicate_in_file", String.valueOf(first));
			}
		}

		@Override
		public Applied apply(Row row) {
			Map<String, Object> n = row.normalized();
			UUID schoolId = this.ctx.schoolId();
			String external = str(n, "externalId");
			if (row.action() == Action.CREATE) {
				String displayName = String.join(" ", java.util.stream.Stream.of(str(n, "givenName"), str(n, "fatherName"),
						str(n, "grandfatherName")).filter(java.util.Objects::nonNull).toList());
				String family = String.join(" ", java.util.stream.Stream.of(str(n, "fatherName"), str(n, "grandfatherName"))
					.filter(java.util.Objects::nonNull)
					.toList());
				NewStaffResult added = StaffHandler.this.people.addStaff(schoolId, this.ctx.actorId(),
						new NewStaff(str(n, "phone"), str(n, "email"), str(n, "givenName"), family.isEmpty() ? null : family,
								displayName, str(n, "staffType"), str(n, "jobTitle"), uuid(n, "campusId"), external));
				return new Applied("CREATED", "STAFF", added.staffId(), external);
			}
			UUID staffId = uuid(n, "matchedId");
			if (staffId == null) {
				throw ApiException.conflict("import.target_missing");
			}
			StaffSnapshot current = StaffHandler.this.people.staff(schoolId, staffId)
				.orElseThrow(() -> ApiException.conflict("import.target_missing"));
			String job = str(n, "jobTitle");
			UUID campus = uuid(n, "campusId");
			boolean changed = (job != null && !job.equals(current.jobTitle()))
					|| (campus != null && !campus.equals(current.campusId()))
					|| (external != null && !external.equals(current.externalRef()));
			if (changed) {
				StaffHandler.this.people.updateStaff(schoolId, this.ctx.actorId(), staffId, job, campus, external);
			}
			return new Applied(changed ? "UPDATED" : "UNCHANGED", "STAFF", staffId, external);
		}

	}

}
