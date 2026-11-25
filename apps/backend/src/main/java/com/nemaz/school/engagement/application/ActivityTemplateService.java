package com.nemaz.school.engagement.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The toolkit's reusable routines, checklists and activity instructions ({@code classroom_activity_templates}, §126).
 * A template is PRIVATE (its owner), CLASS (the teachers of one class) or SCHOOL (every teacher of the school). Teachers
 * and points managers write them; only the owner or a points manager changes or deletes one. What a caller may not see
 * is 404, like an unknown id.
 */
@Service
@Transactional
public class ActivityTemplateService {

	private static final UUID NONE = new UUID(0, 0);

	private static final TypeReference<List<TemplateItem>> ITEMS = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final EngagementAccess access;

	private final AuditLog audit;

	private final JsonMapper json;

	private final Clock clock;

	ActivityTemplateService(JdbcClient jdbc, EngagementAccess access, AuditLog audit, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public CursorPage<TemplateView> list(CurrentUser user, UUID schoolId, UUID classId, TemplateType type, boolean mine,
			String cursor, Integer limit) {
		requireAuthor(user, schoolId);
		if (classId != null) {
			this.access.teachableClass(user, schoolId, classId);
		}
		int pageSize = Cursors.clampLimit(limit);
		UUID after = null;
		if (cursor != null) {
			try {
				after = UUID.fromString(Cursors.decode(cursor, 1).getFirst());
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<TemplateView> rows = this.jdbc.sql(SELECT + """
				 where t.school_id = :s and (t.owner_id = :u or t.visibility = 'SCHOOL' or (t.visibility = 'CLASS' and t.class_id in (:classes)))
				  and (cast(:class as uuid) is null or t.class_id = :class)
				  and (cast(:type as text) is null or t.template_type = :type)
				  and (not :mine or t.owner_id = :u)
				  and (cast(:after as uuid) is null or t.id < :after)
				order by t.id desc limit :n""")
			.param("s", schoolId)
			.param("u", user.userId())
			.param("classes", teachableTemplateClasses(user, schoolId))
			.param("class", classId)
			.param("type", (type != null) ? type.name() : null)
			.param("mine", mine)
			.param("after", after)
			.param("n", pageSize + 1)
			.query((rs, i) -> view(rs, user))
			.list();
		return CursorPage.fromOverfetch(rows, pageSize, (v) -> Cursors.encode(v.id().toString()));
	}

	@Transactional(readOnly = true)
	public TemplateView get(CurrentUser user, UUID schoolId, UUID templateId) {
		requireAuthor(user, schoolId);
		return visible(user, schoolId, templateId);
	}

	public TemplateView create(CurrentUser user, UUID schoolId, TemplateInput in) {
		requireAuthor(user, schoolId);
		TemplateVisibility visibility = (in.visibility() != null) ? in.visibility() : TemplateVisibility.PRIVATE;
		if ((visibility == TemplateVisibility.CLASS) != (in.classId() != null)) {
			throw ApiException.badRequest("points.invalid_template");
		}
		if (in.classId() != null) {
			this.access.teachableClass(user, schoolId, in.classId());
		}
		requireOwnedBySchool(schoolId, in.subjectId(), in.gradeLevelId());
		UUID id = Ids.newId();
		Timestamp now = Timestamp.from(this.clock.instant());
		this.jdbc.sql("""
				insert into classroom_activity_templates (id, school_id, class_id, owner_id, template_type, title, instructions,
				                                          items, visibility, subject_id, grade_level_id, created_at, updated_at)
				values (:id, :s, :class, :u, :type, :title, :instructions, cast(:items as jsonb), :visibility, :subject, :grade,
				        :now, :now)""")
			.param("id", id)
			.param("s", schoolId)
			.param("class", in.classId())
			.param("u", user.userId())
			.param("type", in.templateType().name())
			.param("title", in.title().strip())
			.param("instructions", blankToNull(in.instructions()))
			.param("items", this.json.writeValueAsString((in.items() != null) ? in.items() : List.of()))
			.param("visibility", visibility.name())
			.param("subject", in.subjectId())
			.param("grade", in.gradeLevelId())
			.param("now", now)
			.update();
		this.audit.record(AuditEntry.of("activity_template.create", AuditOutcome.SUCCESS)
			.target("activity_template", id)
			.school(schoolId)
			.metadata("type", in.templateType().name())
			.metadata("visibility", visibility.name()));
		return visible(user, schoolId, id);
	}

	public TemplateView update(CurrentUser user, UUID schoolId, UUID templateId, TemplatePatch p) {
		requireAuthor(user, schoolId);
		TemplateView current = visible(user, schoolId, templateId);
		requireChangeable(user, schoolId, current);
		TemplateVisibility visibility = (p.visibility() != null) ? p.visibility() : current.visibility();
		UUID classId = (p.visibility() != null) ? p.classId() : current.classId();
		if ((visibility == TemplateVisibility.CLASS) != (classId != null)) {
			throw ApiException.badRequest("points.invalid_template");
		}
		if (classId != null && !classId.equals(current.classId())) {
			this.access.teachableClass(user, schoolId, classId);
		}
		requireOwnedBySchool(schoolId, p.subjectId(), p.gradeLevelId());
		this.jdbc.sql("""
				update classroom_activity_templates set
				  title = coalesce(:title, title), template_type = coalesce(:type, template_type),
				  instructions = case when :setInstructions then :instructions else instructions end,
				  items = case when :setItems then cast(:items as jsonb) else items end,
				  visibility = :visibility, class_id = :class,
				  subject_id = coalesce(:subject, subject_id), grade_level_id = coalesce(:grade, grade_level_id),
				  updated_at = :now, version = version + 1
				where id = :id and school_id = :s""")
			.param("title", (p.title() != null) ? p.title().strip() : null)
			.param("type", (p.templateType() != null) ? p.templateType().name() : null)
			.param("setInstructions", p.instructions() != null)
			.param("instructions", blankToNull(p.instructions()))
			.param("setItems", p.items() != null)
			.param("items", this.json.writeValueAsString((p.items() != null) ? p.items() : List.of()))
			.param("visibility", visibility.name())
			.param("class", classId)
			.param("subject", p.subjectId())
			.param("grade", p.gradeLevelId())
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", templateId)
			.param("s", schoolId)
			.update();
		this.audit.record(AuditEntry.of("activity_template.update", AuditOutcome.SUCCESS)
			.target("activity_template", templateId)
			.school(schoolId));
		return visible(user, schoolId, templateId);
	}

	public void delete(CurrentUser user, UUID schoolId, UUID templateId) {
		requireAuthor(user, schoolId);
		TemplateView current = visible(user, schoolId, templateId);
		requireChangeable(user, schoolId, current);
		this.jdbc.sql("delete from classroom_activity_templates where id = :id and school_id = :s")
			.param("id", templateId)
			.param("s", schoolId)
			.update();
		this.audit.record(AuditEntry.of("activity_template.delete", AuditOutcome.SUCCESS)
			.target("activity_template", templateId)
			.school(schoolId));
	}

	// --- access -----------------------------------------------------------------------------

	/** Teachers and points managers use templates: 404 for a stranger to the school, 403 for other members. */
	private void requireAuthor(CurrentUser user, UUID schoolId) {
		this.access.gate().requireVisible(user, schoolId);
		if (!this.access.pointsManager(user, schoolId) && !this.access.activeTeacher(user, schoolId)) {
			throw ApiException.forbidden("points.access_denied");
		}
	}

	private void requireChangeable(CurrentUser user, UUID schoolId, TemplateView template) {
		if (!template.ownerId().equals(user.userId()) && !this.access.pointsManager(user, schoolId)) {
			throw ApiException.forbidden("points.access_denied");
		}
	}

	private TemplateView visible(CurrentUser user, UUID schoolId, UUID templateId) {
		Optional<TemplateView> found = this.jdbc.sql(SELECT + " where t.id = :id and t.school_id = :s")
			.param("id", templateId)
			.param("s", schoolId)
			.query((rs, i) -> view(rs, user))
			.optional();
		TemplateView template = found.orElseThrow(() -> ApiException.notFound("points.template_not_found"));
		boolean owner = template.ownerId().equals(user.userId());
		boolean classTeacher = template.visibility() == TemplateVisibility.CLASS && template.classId() != null
				&& this.access.classes().canTeach(user, template.classId());
		if (!owner && template.visibility() != TemplateVisibility.SCHOOL && !classTeacher) {
			throw ApiException.notFound("points.template_not_found");
		}
		return template;
	}

	private Set<UUID> teachableTemplateClasses(CurrentUser user, UUID schoolId) {
		Set<UUID> teachable = new LinkedHashSet<>();
		teachable.add(NONE);
		for (UUID classId : this.jdbc.sql("select distinct class_id from classroom_activity_templates where school_id = :s and visibility = 'CLASS'")
			.param("s", schoolId)
			.query(UUID.class)
			.list()) {
			if (this.access.classes().canTeach(user, classId)) {
				teachable.add(classId);
			}
		}
		return teachable;
	}

	private void requireOwnedBySchool(UUID schoolId, UUID subjectId, UUID gradeLevelId) {
		if (subjectId != null && !this.jdbc.sql("select exists (select 1 from subjects where id = :id and school_id = :s)")
			.param("id", subjectId)
			.param("s", schoolId)
			.query(Boolean.class)
			.single()) {
			throw ApiException.notFound("academic.subject_not_found");
		}
		if (gradeLevelId != null && !this.jdbc.sql("select exists (select 1 from grade_levels where id = :id and school_id = :s)")
			.param("id", gradeLevelId)
			.param("s", schoolId)
			.query(Boolean.class)
			.single()) {
			throw ApiException.notFound("academic.grade_not_found");
		}
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	private static final String SELECT = """
			select t.id, t.school_id, t.class_id, t.owner_id, u.display_name as owner_name, t.template_type, t.title, t.instructions,
			       t.items, t.visibility, t.subject_id, t.grade_level_id, t.created_at, t.updated_at
			from classroom_activity_templates t join users u on u.id = t.owner_id""";

	private TemplateView view(ResultSet rs, CurrentUser user) throws SQLException {
		String items = rs.getString("items");
		List<TemplateItem> parsed = (items == null) ? List.of() : this.json.readValue(items, ITEMS);
		return new TemplateView(Rows.uuid(rs, "id"), Rows.uuid(rs, "class_id"), Rows.uuid(rs, "owner_id"),
				rs.getString("owner_name"), user.userId().equals(Rows.uuid(rs, "owner_id")),
				TemplateType.valueOf(rs.getString("template_type")), rs.getString("title"), rs.getString("instructions"),
				new ArrayList<>(parsed), TemplateVisibility.valueOf(rs.getString("visibility")), Rows.uuid(rs, "subject_id"),
				Rows.uuid(rs, "grade_level_id"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"));
	}

	// --- shapes -----------------------------------------------------------------------------

	public enum TemplateType {

		ROUTINE, CHECKLIST, ACTIVITY, LESSON_PREP

	}

	public enum TemplateVisibility {

		PRIVATE, CLASS, SCHOOL

	}

	/** One step of a routine or checklist. */
	public record TemplateItem(String text, Integer minutes) {
	}

	public record TemplateInput(TemplateType templateType, String title, String instructions, List<TemplateItem> items,
			TemplateVisibility visibility, UUID classId, UUID subjectId, UUID gradeLevelId) {
	}

	public record TemplatePatch(TemplateType templateType, String title, String instructions, List<TemplateItem> items,
			TemplateVisibility visibility, UUID classId, UUID subjectId, UUID gradeLevelId) {
	}

	public record TemplateView(@NotNull UUID id, UUID classId, @NotNull UUID ownerId, @NotNull String ownerName,
			@Schema(description = "True when the caller wrote it (and may change or delete it)") boolean mine,
			@NotNull TemplateType templateType, @NotNull String title, String instructions,
			@NotNull List<TemplateItem> items, @NotNull TemplateVisibility visibility, UUID subjectId, UUID gradeLevelId,
			@NotNull Instant createdAt, @NotNull Instant updatedAt) {
	}

}
