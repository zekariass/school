package com.nemaz.school.engagement.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Point categories ("skills"): the school's copies of the platform templates, the school's own, and class-level custom
 * ones. Categories are never deleted (the ledger refers to them): they are deactivated.
 * <ul>
 * <li><b>School level</b> ({@code school.points.manage}): create, rename, recolor, change default points, deactivate;
 * HOME categories (for guardians) live here too and are POSITIVE only.</li>
 * <li><b>Class level:</b> the class's LEAD or CO_TEACHER (and class managers, points managers) add categories used in
 * that class only (CLASS context, any polarity).</li>
 * <li><b>Listing:</b> teachers of the class see the school's CLASS categories plus the class's own, active ones;
 * NEEDS_WORK categories are left out while {@code engagement.needs_work.enabled} is off (editors asking with
 * {@code includeInactive} see everything).</li>
 * </ul>
 */
@Service
@Transactional
public class CategoryService {

	static final int MAX_CLASS_CATEGORIES = 40;

	private final JdbcClient jdbc;

	private final EngagementAccess access;

	private final EngagementSeeding seeding;

	private final EngagementSettings settings;

	private final AuditLog audit;

	private final JsonMapper json;

	private final Clock clock;

	CategoryService(JdbcClient jdbc, EngagementAccess access, EngagementSeeding seeding, EngagementSettings settings,
			AuditLog audit, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.seeding = seeding;
		this.settings = settings;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	// --- reads ------------------------------------------------------------------------------

	public List<CategoryView> list(CurrentUser user, UUID schoolId, UUID classId, PointContext context,
			boolean includeInactive) {
		boolean manager = this.access.pointsManager(user, schoolId);
		boolean editor = manager;
		if (classId == null) {
			this.access.gate().requireVisible(user, schoolId);
		}
		if (classId != null) {
			ClassRef ref = this.access.teachableClass(user, schoolId, classId);
			editor = this.access.mayEditClassCategories(user, ref);
		}
		else if (!manager && !this.access.activeTeacher(user, schoolId)) {
			throw ApiException.forbidden("points.access_denied");
		}
		this.seeding.ensure(schoolId);
		boolean all = includeInactive && editor;
		boolean needsWork = this.settings.needsWork(schoolId);
		List<CategoryView> result = new ArrayList<>();
		this.jdbc.sql(SELECT + " " + """
				where school_id = :s and (class_id is null or class_id = cast(:c as uuid))
				  and (cast(:ctx as text) is null or context = :ctx)
				  and (:all or is_active)
				  and (:all or :needsWork or polarity = 'POSITIVE')
				  and (class_id is null or :classScoped)
				order by sort_order, id""")
			.param("s", schoolId)
			.param("c", classId)
			.param("ctx", (context != null) ? context.name() : null)
			.param("all", all)
			.param("needsWork", needsWork)
			.param("classScoped", classId != null)
			.query(this::row)
			.list()
			.forEach((c) -> result.add(view(c)));
		// Teachers award in the CLASS context; HOME categories only show to a caller who asks for them (managers).
		if (context == null && (classId != null || !manager)) {
			result.removeIf((c) -> c.context() == PointContext.HOME);
		}
		return result;
	}

	/** The guardian-facing list: the school's active HOME categories. */
	public List<CategoryView> homeCategories(UUID schoolId) {
		this.seeding.ensure(schoolId);
		List<CategoryView> result = new ArrayList<>();
		this.jdbc.sql(SELECT + " where school_id = :s and class_id is null and context = 'HOME' and is_active order by sort_order, id")
			.param("s", schoolId)
			.query(this::row)
			.list()
			.forEach((c) -> result.add(view(c)));
		return result;
	}

	// --- writes -----------------------------------------------------------------------------

	public CategoryView create(CurrentUser user, UUID schoolId, CategoryInput in) {
		this.access.gate().require(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
		this.seeding.ensure(schoolId);
		PointContext context = (in.context() != null) ? in.context() : PointContext.CLASS;
		PointPolarity polarity = (in.polarity() != null) ? in.polarity() : PointPolarity.POSITIVE;
		if (context == PointContext.SCHOOL) {
			throw ApiException.badRequest("points.invalid_category");
		}
		if (context == PointContext.HOME && polarity != PointPolarity.POSITIVE) {
			throw ApiException.badRequest("points.home_category_positive_only");
		}
		return insert(user, schoolId, null, in, context, polarity);
	}

	public CategoryView createForClass(CurrentUser user, UUID schoolId, UUID classId, CategoryInput in) {
		ClassRef ref = this.access.teachableClass(user, schoolId, classId);
		if (!this.access.mayEditClassCategories(user, ref)) {
			throw ApiException.forbidden("points.access_denied");
		}
		if (in.context() != null && in.context() != PointContext.CLASS) {
			throw ApiException.badRequest("points.invalid_category");
		}
		this.seeding.ensure(schoolId);
		int count = this.jdbc.sql("select count(*) from point_categories where class_id = :c and is_active")
			.param("c", classId)
			.query(Integer.class)
			.single();
		if (count >= MAX_CLASS_CATEGORIES) {
			throw ApiException.conflict("points.category_limit");
		}
		return insert(user, schoolId, classId, in, PointContext.CLASS,
				(in.polarity() != null) ? in.polarity() : PointPolarity.POSITIVE);
	}

	public CategoryView update(CurrentUser user, UUID schoolId, UUID categoryId, CategoryPatch patch) {
		this.access.gate().require(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
		Category current = find(schoolId, categoryId).filter((c) -> c.classId() == null)
			.orElseThrow(() -> ApiException.notFound("points.category_not_found"));
		return apply(user, current, patch);
	}

	public CategoryView updateForClass(CurrentUser user, UUID schoolId, UUID classId, UUID categoryId, CategoryPatch patch) {
		ClassRef ref = this.access.teachableClass(user, schoolId, classId);
		Category current = find(schoolId, categoryId).filter((c) -> classId.equals(c.classId()))
			.orElseThrow(() -> ApiException.notFound("points.category_not_found"));
		if (!this.access.mayEditClassCategories(user, ref)) {
			throw ApiException.forbidden("points.access_denied");
		}
		return apply(user, current, patch);
	}

	private CategoryView insert(CurrentUser user, UUID schoolId, UUID classId, CategoryInput in, PointContext context,
			PointPolarity polarity) {
		UUID id = Ids.newId();
		int points = (in.defaultPoints() != null) ? in.defaultPoints() : 1;
		this.jdbc.sql("""
				insert into point_categories (id, school_id, class_id, name_i18n, icon, color, default_points, polarity, context,
				                              sort_order, created_by, created_at, updated_at)
				values (:id, :s, :c, cast(:names as jsonb), :icon, :color, :points, :polarity, :context, :sort, :by, :now, :now)""")
			.param("id", id)
			.param("s", schoolId)
			.param("c", classId)
			.param("names", Names.write(this.json, cleanNames(in.names())))
			.param("icon", in.icon())
			.param("color", in.color())
			.param("points", points)
			.param("polarity", polarity.name())
			.param("context", context.name())
			.param("sort", (in.sortOrder() != null) ? in.sortOrder() : 500)
			.param("by", user.userId())
			.param("now", java.sql.Timestamp.from(this.clock.instant()))
			.update();
		this.audit.record(AuditEntry.of("points.category.create", AuditOutcome.SUCCESS)
			.target("point_category", id)
			.school(schoolId)
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("polarity", polarity.name())
			.metadata("context", context.name()));
		return view(find(schoolId, id).orElseThrow());
	}

	private CategoryView apply(CurrentUser user, Category current, CategoryPatch p) {
		if (p.names() != null) {
			this.jdbc.sql("update point_categories set name_i18n = cast(:v as jsonb) where id = :id")
				.param("v", Names.write(this.json, cleanNames(p.names())))
				.param("id", current.id())
				.update();
		}
		if (p.icon() != null) {
			this.jdbc.sql("update point_categories set icon = :v where id = :id").param("v", p.icon()).param("id", current.id()).update();
		}
		if (p.color() != null) {
			this.jdbc.sql("update point_categories set color = :v where id = :id").param("v", p.color()).param("id", current.id()).update();
		}
		if (p.defaultPoints() != null) {
			this.jdbc.sql("update point_categories set default_points = :v where id = :id")
				.param("v", p.defaultPoints())
				.param("id", current.id())
				.update();
		}
		if (p.sortOrder() != null) {
			this.jdbc.sql("update point_categories set sort_order = :v where id = :id")
				.param("v", p.sortOrder())
				.param("id", current.id())
				.update();
		}
		if (p.active() != null) {
			this.jdbc.sql("update point_categories set is_active = :v where id = :id").param("v", p.active()).param("id", current.id()).update();
		}
		this.jdbc.sql("update point_categories set updated_at = :now, version = version + 1 where id = :id")
			.param("now", java.sql.Timestamp.from(this.clock.instant()))
			.param("id", current.id())
			.update();
		this.audit.record(AuditEntry.of("points.category.update", AuditOutcome.SUCCESS)
			.target("point_category", current.id())
			.school(current.schoolId())
			.metadata("fields", changedFields(p)));
		return view(find(current.schoolId(), current.id()).orElseThrow());
	}

	private static List<String> changedFields(CategoryPatch p) {
		List<String> fields = new ArrayList<>();
		if (p.names() != null) {
			fields.add("names");
		}
		if (p.icon() != null) {
			fields.add("icon");
		}
		if (p.color() != null) {
			fields.add("color");
		}
		if (p.defaultPoints() != null) {
			fields.add("defaultPoints");
		}
		if (p.sortOrder() != null) {
			fields.add("sortOrder");
		}
		if (p.active() != null) {
			fields.add("active");
		}
		return fields;
	}

	private static Map<String, String> cleanNames(Map<String, String> names) {
		Map<String, String> clean = new LinkedHashMap<>();
		names.forEach((language, text) -> {
			if (language != null && language.matches("^[a-z]{2,3}$") && text != null && !text.isBlank()) {
				clean.put(language, text.strip());
			}
		});
		if (clean.isEmpty()) {
			throw ApiException.badRequest("points.invalid_category");
		}
		return clean;
	}

	// --- lookups for the other services ------------------------------------------------------

	Optional<Category> find(UUID schoolId, UUID categoryId) {
		return this.jdbc.sql(SELECT + " where id = :id and school_id = :s")
			.param("id", categoryId)
			.param("s", schoolId)
			.query(this::row)
			.optional();
	}

	/** The school's own copy of a platform template, by its template code. */
	Optional<Category> findTemplateCopy(UUID schoolId, String templateCode) {
		this.seeding.ensure(schoolId);
		return this.jdbc.sql(SELECT + " where school_id = :s and class_id is null and template_code = :t")
			.param("s", schoolId)
			.param("t", templateCode)
			.query(this::row)
			.optional();
	}

	private static final String SELECT = """
			select id, school_id, class_id, template_code, name_i18n, icon, color, default_points, polarity, context, is_active,
			       sort_order from point_categories""";

	private Category row(ResultSet rs, int i) throws SQLException {
		return new Category(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), Rows.uuid(rs, "class_id"),
				rs.getString("template_code"), Names.read(this.json, rs.getString("name_i18n")), rs.getString("icon"),
				rs.getString("color"), rs.getInt("default_points"), PointPolarity.valueOf(rs.getString("polarity")),
				PointContext.valueOf(rs.getString("context")), rs.getBoolean("is_active"), rs.getInt("sort_order"));
	}

	private CategoryView view(Category c) {
		Locale locale = LocaleContextHolder.getLocale();
		return new CategoryView(c.id(), c.classId(), c.templateCode(), Names.pick(c.names(), locale), c.names(), c.icon(),
				c.color(), c.defaultPoints(), c.polarity(), c.context(), c.active(), c.sortOrder());
	}

	/** A category row. */
	record Category(UUID id, UUID schoolId, UUID classId, String templateCode, Map<String, String> names, String icon,
			String color, int defaultPoints, PointPolarity polarity, PointContext context, boolean active, int sortOrder) {
	}

	/** Named explicitly: the notification preferences also have a CategoryView and OpenAPI schemas are keyed by name. */
	@Schema(name = "PointCategoryView")
	public record CategoryView(@NotNull UUID id, @Schema(description = "Set for a category of one class") UUID classId,
			@Schema(description = "Set for a copy of a platform template; the stable key of default categories") String templateCode,
			@NotNull @Schema(description = "The name in the caller's language") String name,
			@NotNull @Schema(description = "The names by language code") Map<String, String> names, String icon, String color,
			int defaultPoints, @NotNull PointPolarity polarity, @NotNull PointContext context, boolean active,
			int sortOrder) {
	}

	public record CategoryInput(Map<String, String> names, String icon, String color, Integer defaultPoints,
			PointPolarity polarity, PointContext context, Integer sortOrder) {
	}

	public record CategoryPatch(Map<String, String> names, String icon, String color, Integer defaultPoints,
			Integer sortOrder, Boolean active) {
	}

}
