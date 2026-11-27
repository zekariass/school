package com.nemaz.school.portfolio.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMemberships.StudentClass;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.portfolio.PortfolioItemReviewed;
import com.nemaz.school.portfolio.PortfolioItemSubmitted;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemType;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemUpdateRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioMode;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioQuickCaptureRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;
import com.nemaz.school.portfolio.application.PortfolioWorkflow.Move;
import com.nemaz.school.system.RateLimiter;
import com.nemaz.school.tenancy.CountryResolver;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, editing, submitting and deleting items (rule book P1 and P2). Students write about their own work; teachers quick-capture
 * for the students of a class they teach (auto-approved). Every change is audited in its transaction with ids, status, visibility
 * and counts only, never the text, and publishes its event there.
 */
@Service
public class PortfolioItemService {

	static final int ITEMS_PER_DAY = 100;

	static final int MAX_TAGS = 20;

	private final JdbcClient jdbc;

	private final PortfolioAccess access;

	private final PortfolioAudience audience;

	private final PortfolioConsent consent;

	private final PortfolioMedia media;

	private final PortfolioViews views;

	private final PortfolioSettings settings;

	private final PortfolioLedger ledger;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final RateLimiter limiter;

	private final Clock clock;

	private final CountryResolver countries;

	PortfolioItemService(JdbcClient jdbc, PortfolioAccess access, PortfolioAudience audience, PortfolioConsent consent,
			PortfolioMedia media, PortfolioViews views, PortfolioSettings settings, PortfolioLedger ledger, AuditLog audit,
			ApplicationEventPublisher events, RateLimiter limiter, Clock clock, CountryResolver countries) {
		this.countries = countries;
		this.jdbc = jdbc;
		this.access = access;
		this.audience = audience;
		this.consent = consent;
		this.media = media;
		this.views = views;
		this.settings = settings;
		this.ledger = ledger;
		this.audit = audit;
		this.events = events;
		this.limiter = limiter;
		this.clock = clock;
	}

	/** The cleaned fields shared by creation and quick capture. */
	private record Fields(String title, String description, String reflection, String textContent, LocalDate workDate) {
	}

	// --- create ----------------------------------------------------------------------------------

	@Transactional
	public PortfolioItemView create(CurrentUser user, UUID schoolId, PortfolioItemRequest req) {
		this.access.requireStudentWriter(user, schoolId);
		requireSupported(req.itemType());
		this.limiter.consume("portfolio-create", user.userId().toString(), ITEMS_PER_DAY, Duration.ofDays(1), "portfolio.rate_limited");
		UUID studentId = user.userId();
		Fields fields = fields(schoolId, req.itemType(), req.title(), req.description(), req.reflection(), req.textContent(),
				req.workDate());
		List<UUID> mediaIds = list(req.mediaIds());
		PortfolioMedia.validate(req.itemType(), mediaIds);
		UUID classId = classOf(studentId, schoolId, req.classId());
		requireSubject(schoolId, req.subjectId());
		PortfolioVisibility visibility = (req.visibility() != null) ? req.visibility() : PortfolioVisibility.GUARDIANS;
		PortfolioMode mode = (req.mode() != null) ? req.mode() : PortfolioMode.DRAFT;
		Set<UUID> tags = tags(schoolId, studentId, req.taggedStudentIds());
		boolean showsOthers = Boolean.TRUE.equals(req.showsOtherChildren()) || !tags.isEmpty();
		if (mode == PortfolioMode.SUBMIT) {
			requireComplete(req.itemType(), fields.textContent(), mediaIds.size());
		}
		UUID portfolioId = portfolioOf(studentId, schoolId);
		UUID id = Ids.newId();
		Instant now = this.clock.instant();
		boolean submit = mode == PortfolioMode.SUBMIT;
		boolean confirm = Boolean.TRUE.equals(req.confirmNoUnconsentedStudents()) && !mediaIds.isEmpty();
		insertItem(id, portfolioId, schoolId, studentId, classId, req.subjectId(), user.userId(), req.itemType(), fields, submit
				? PortfolioStatus.SUBMITTED : PortfolioStatus.DRAFT, visibility, showsOthers, confirm ? user.userId() : null,
				confirm ? now : null, submit ? now : null);
		this.media.attach(user, schoolId, req.itemType(), id, mediaIds);
		insertTags(id, tags);
		this.audit.record(AuditEntry.of("portfolio.item.create", AuditOutcome.SUCCESS)
			.target("portfolio_item", id)
			.school(schoolId)
			.metadata("itemType", req.itemType().name())
			.metadata("status", submit ? "SUBMITTED" : "DRAFT")
			.metadata("visibility", visibility.name())
			.metadata("mediaCount", mediaIds.size())
			.metadata("taggedCount", tags.size()));
		if (confirm) {
			auditConfirmation(id, schoolId);
		}
		if (submit) {
			UUID approval = this.ledger.append(id, "SUBMITTED", null, PortfolioStatus.SUBMITTED, user.userId(), null);
			this.events.publishEvent(new PortfolioItemSubmitted(id, schoolId, approval));
		}
		return view(user, id);
	}

	// --- quick capture ---------------------------------------------------------------------------

	@Transactional
	public PortfolioItemView quickCapture(CurrentUser user, UUID schoolId, UUID classId, PortfolioQuickCaptureRequest req) {
		this.access.requireSchool(user, schoolId);
		ClassRef ref = this.access.viewableClass(user, schoolId, classId);
		if (!this.access.canCapture(user, ref)) {
			throw ApiException.forbidden("portfolio.capture_not_allowed");
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		if (!this.access.schoolActive(schoolId)) {
			throw ApiException.forbidden("portfolio.school_inactive");
		}
		if (!this.access.memberships().activeStudentIds(classId).contains(req.studentId())) {
			throw ApiException.notFound("student.not_found");
		}
		requireSupported(req.itemType());
		this.limiter.consume("portfolio-create", user.userId().toString(), ITEMS_PER_DAY, Duration.ofDays(1), "portfolio.rate_limited");
		UUID studentId = req.studentId();
		Fields fields = fields(schoolId, req.itemType(), req.title(), req.description(), req.reflection(), req.textContent(),
				req.workDate());
		List<UUID> mediaIds = list(req.mediaIds());
		PortfolioMedia.validate(req.itemType(), mediaIds);
		requireComplete(req.itemType(), fields.textContent(), mediaIds.size());
		requireSubject(schoolId, req.subjectId());
		PortfolioVisibility visibility = (req.visibility() != null) ? req.visibility() : PortfolioVisibility.GUARDIANS;
		Set<UUID> tags = tags(schoolId, studentId, req.taggedStudentIds());
		// restrictive: a capture that does not say whether other children appear is treated as showing them
		boolean showsOthers = (req.showsOtherChildren() == null) || req.showsOtherChildren() || !tags.isEmpty();
		boolean confirm = Boolean.TRUE.equals(req.confirmNoUnconsentedStudents()) && !mediaIds.isEmpty();
		this.consent.check(schoolId, studentId, showsOthers, this.settings.effective(visibility, schoolId), !mediaIds.isEmpty(), tags,
				confirm, this.access.classes().canViewRoster(user, classId));
		UUID portfolioId = portfolioOf(studentId, schoolId);
		UUID id = Ids.newId();
		Instant now = this.clock.instant();
		insertItem(id, portfolioId, schoolId, studentId, classId, req.subjectId(), user.userId(), req.itemType(), fields,
				PortfolioStatus.APPROVED, visibility, showsOthers, confirm ? user.userId() : null, confirm ? now : null, null);
		this.jdbc.sql("update portfolio_items set reviewed_by = :u, reviewed_at = :t where id = :id")
			.param("u", user.userId())
			.param("t", Timestamp.from(now))
			.param("id", id)
			.update();
		this.media.attach(user, schoolId, req.itemType(), id, mediaIds);
		insertTags(id, tags);
		UUID approval = this.ledger.append(id, "CAPTURED", null, PortfolioStatus.APPROVED, user.userId(), null);
		this.audit.record(AuditEntry.of("portfolio.item.quick_capture", AuditOutcome.SUCCESS)
			.target("portfolio_item", id)
			.school(schoolId)
			.metadata("classId", classId.toString())
			.metadata("itemType", req.itemType().name())
			.metadata("visibility", visibility.name())
			.metadata("mediaCount", mediaIds.size())
			.metadata("taggedCount", tags.size())
			.metadata("showsOtherChildren", showsOthers));
		if (confirm) {
			auditConfirmation(id, schoolId);
		}
		this.events.publishEvent(new PortfolioItemReviewed(id, schoolId, studentId, approval, "APPROVED", true));
		return view(user, id);
	}

	// --- update, submit, delete ------------------------------------------------------------------

	@Transactional
	public PortfolioItemView update(CurrentUser user, UUID schoolId, UUID itemId, PortfolioItemUpdateRequest req) {
		ItemRow item = studentItem(user, schoolId, itemId);
		if (item.status() != PortfolioStatus.DRAFT && item.status() != PortfolioStatus.CHANGES_REQUESTED) {
			throw ApiException.conflict("portfolio.invalid_state");
		}
		item = lock(itemId);
		List<String> changed = new ArrayList<>();
		String title = item.title();
		if (req.title() != null) {
			title = PortfolioText.required(req.title(), PortfolioText.TITLE_MAX, "title");
			changed.add("title");
		}
		String description = (req.description() != null) ? PortfolioText.optional(req.description(), PortfolioText.DESCRIPTION_MAX, "description") : item.description();
		String reflection = (req.reflection() != null) ? PortfolioText.optional(req.reflection(), PortfolioText.REFLECTION_MAX, "reflection") : item.reflection();
		String text = item.textContent();
		if (req.textContent() != null) {
			if (item.itemType() != PortfolioItemType.TEXT) {
				throw ApiException.badRequest("portfolio.text_content_not_allowed");
			}
			text = PortfolioText.optional(req.textContent(), PortfolioText.TEXT_MAX, "text_content");
		}
		LocalDate workDate = (req.workDate() != null) ? checkWorkDate(schoolId, req.workDate()) : item.workDate();
		UUID classId = (req.classId() != null) ? classOf(item.studentId(), schoolId, req.classId()) : item.classId();
		UUID subjectId = (req.subjectId() != null) ? req.subjectId() : item.subjectId();
		requireSubject(schoolId, subjectId);
		PortfolioVisibility visibility = (req.visibility() != null) ? req.visibility() : item.visibility();
		boolean filesChanged = false;
		if (req.mediaIds() != null) {
			PortfolioMedia.validate(item.itemType(), req.mediaIds());
			filesChanged = this.media.replace(user, schoolId, item.itemType(), itemId, req.mediaIds());
		}
		Set<UUID> tags = new LinkedHashSet<>(this.consent.tagsOf(List.of(itemId)).getOrDefault(itemId, Set.of()));
		boolean tagsChanged = false;
		if (req.taggedStudentIds() != null) {
			Set<UUID> wanted = tags(schoolId, item.studentId(), req.taggedStudentIds());
			tagsChanged = !wanted.equals(tags);
			if (tagsChanged) {
				this.jdbc.sql("delete from portfolio_item_tags where item_id = :i").param("i", itemId).update();
				insertTags(itemId, wanted);
			}
			tags = wanted;
		}
		boolean showsOthers = (req.showsOtherChildren() != null) ? req.showsOtherChildren() || !tags.isEmpty()
				: item.showsOthers() || !tags.isEmpty();
		// the confirmation is the creator's word about these files and tags: it goes when either changes
		boolean confirmed = item.hasConfirmation() && !filesChanged && !tagsChanged;
		boolean newConfirmation = Boolean.TRUE.equals(req.confirmNoUnconsentedStudents()) && !this.media.mediaIdsOf(itemId).isEmpty();
		this.jdbc.sql("""
				update portfolio_items set title = :title, description = :description, reflection = :reflection, text_content = :text,
				  work_date = :workDate, class_id = :class, subject_id = :subject, visibility = :visibility,
				  shows_other_children = :others, updated_at = :now where id = :id""")
			.param("title", title)
			.param("description", description)
			.param("reflection", reflection)
			.param("text", text)
			.param("workDate", (workDate != null) ? java.sql.Date.valueOf(workDate) : null)
			.param("class", classId)
			.param("subject", subjectId)
			.param("visibility", visibility.name())
			.param("others", showsOthers)
			.param("now", Timestamp.from(this.clock.instant()))
			.param("id", itemId)
			.update();
		if (newConfirmation) {
			this.jdbc.sql("update portfolio_items set media_confirmed_by = :u, media_confirmed_at = :t where id = :id")
				.param("u", user.userId())
				.param("t", Timestamp.from(this.clock.instant()))
				.param("id", itemId)
				.update();
			auditConfirmation(itemId, schoolId);
		}
		else if (!confirmed && item.hasConfirmation()) {
			this.jdbc.sql("update portfolio_items set media_confirmed_by = null, media_confirmed_at = null where id = :id")
				.param("id", itemId)
				.update();
		}
		if (req.description() != null) {
			changed.add("description");
		}
		if (req.reflection() != null) {
			changed.add("reflection");
		}
		if (req.textContent() != null) {
			changed.add("textContent");
		}
		if (filesChanged) {
			changed.add("media");
		}
		if (tagsChanged) {
			changed.add("tags");
		}
		if (req.visibility() != null) {
			changed.add("visibility");
		}
		this.audit.record(AuditEntry.of("portfolio.item.update", AuditOutcome.SUCCESS)
			.target("portfolio_item", itemId)
			.school(schoolId)
			.metadata("fields", String.join(",", changed))
			.metadata("status", item.status().name()));
		return view(user, itemId);
	}

	@Transactional
	public PortfolioItemView submit(CurrentUser user, UUID schoolId, UUID itemId) {
		ItemRow item = studentItem(user, schoolId, itemId);
		item = lock(itemId);
		PortfolioStatus to = PortfolioWorkflow.require(item.status(), Move.SUBMIT);
		requireComplete(item.itemType(), item.textContent(), this.media.mediaIdsOf(itemId).size());
		Instant now = this.clock.instant();
		this.jdbc.sql("update portfolio_items set status = 'SUBMITTED', submitted_at = :t, updated_at = :t where id = :id")
			.param("t", Timestamp.from(now))
			.param("id", itemId)
			.update();
		UUID approval = this.ledger.append(itemId, "SUBMITTED", item.status(), to, user.userId(), null);
		this.audit.record(AuditEntry.of("portfolio.item.submit", AuditOutcome.SUCCESS)
			.target("portfolio_item", itemId)
			.school(schoolId)
			.metadata("from", item.status().name())
			.metadata("resubmission", item.status() == PortfolioStatus.CHANGES_REQUESTED));
		this.events.publishEvent(new PortfolioItemSubmitted(itemId, schoolId, approval));
		return view(user, itemId);
	}

	/**
	 * Deletes an item (soft): the student's own DRAFT, SUBMITTED or CHANGES_REQUESTED item, or any item by a reviewer. The text of
	 * the item and its comments is erased, the files are discarded, the history and the audit rows stay as a tombstone.
	 * Data-subject erasure of an APPROVED item uses the same operation without the reviewer check (documented hook, not built).
	 */
	@Transactional
	public void delete(CurrentUser user, UUID schoolId, UUID itemId) {
		this.access.requireSchool(user, schoolId);
		ItemRow item = this.audience.find(user, itemId).filter((i) -> i.schoolId().equals(schoolId)).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
		boolean own = item.studentId().equals(user.userId()) && item.createdBy().equals(item.studentId())
				&& (item.status() == PortfolioStatus.DRAFT || item.status() == PortfolioStatus.SUBMITTED
						|| item.status() == PortfolioStatus.CHANGES_REQUESTED);
		boolean reviewer = item.status() != PortfolioStatus.DRAFT && this.audience.reviews(user, itemId);
		if (!own && !reviewer) {
			throw ApiException.forbidden("portfolio.delete_not_allowed");
		}
		item = lock(itemId);
		erase(item, user.userId());
		this.audit.record(AuditEntry.of("portfolio.item.delete", AuditOutcome.SUCCESS)
			.target("portfolio_item", itemId)
			.school(schoolId)
			.metadata("status", item.status().name())
			.metadata("by", own ? "STUDENT" : "REVIEWER"));
	}

	/** The soft delete: tombstone row, text erased, files discarded, comments erased (also the hook for data-subject erasure). */
	void erase(ItemRow item, UUID actor) {
		this.ledger.append(item.id(), "DELETED", item.status(), null, actor, null);
		this.jdbc.sql("""
				update portfolio_items set deleted_at = :now, deleted_by = :actor, title = null, description = null, reflection = null,
				  text_content = null, is_featured = false, updated_at = :now where id = :id""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("actor", actor)
			.param("id", item.id())
			.update();
		this.jdbc.sql("delete from portfolio_item_tags where item_id = :id").param("id", item.id()).update();
		this.jdbc.sql("update portfolio_comments set status = 'DELETED', body = null where item_id = :id").param("id", item.id()).update();
		this.media.discardAll(item.id());
	}

	// --- helpers -----------------------------------------------------------------------------------

	/** The item of a student who writes about it (the student who created it); 404 when not visible, 403 for others. */
	private ItemRow studentItem(CurrentUser user, UUID schoolId, UUID itemId) {
		this.access.requireSchool(user, schoolId);
		ItemRow item = this.audience.find(user, itemId).filter((i) -> i.schoolId().equals(schoolId)).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
		if (!item.studentId().equals(user.userId()) || !item.createdBy().equals(item.studentId())) {
			throw ApiException.forbidden("portfolio.access_denied");
		}
		this.access.requireStudentWriter(user, schoolId);
		return item;
	}

	/** Locks the row and returns it as it is now (another request may have moved it since it was read). */
	private ItemRow lock(UUID itemId) {
		this.jdbc.sql("select id from portfolio_items where id = :id for update").param("id", itemId).query(UUID.class).optional();
		return this.audience.raw(itemId).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
	}

	private PortfolioItemView view(CurrentUser user, UUID itemId) {
		ItemRow row = this.audience.raw(itemId).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
		return this.views.view(user, row);
	}

	private static void requireSupported(PortfolioItemType type) {
		if (type == PortfolioItemType.AUDIO || type == PortfolioItemType.VIDEO) {
			throw ApiException.unprocessable("portfolio.item_type_unsupported");
		}
	}

	private static <T> List<T> list(List<T> list) {
		return (list != null) ? list : List.of();
	}

	private Fields fields(UUID schoolId, PortfolioItemType type, String title, String description, String reflection, String text,
			LocalDate workDate) {
		String cleanTitle = PortfolioText.required(title, PortfolioText.TITLE_MAX, "title");
		String cleanText = PortfolioText.optional(text, PortfolioText.TEXT_MAX, "text_content");
		if (cleanText != null && type != PortfolioItemType.TEXT) {
			throw ApiException.badRequest("portfolio.text_content_not_allowed");
		}
		return new Fields(cleanTitle, PortfolioText.optional(description, PortfolioText.DESCRIPTION_MAX, "description"),
				PortfolioText.optional(reflection, PortfolioText.REFLECTION_MAX, "reflection"), cleanText,
				checkWorkDate(schoolId, workDate));
	}

	/** The work date (today in the school's time zone when absent); a date in the future is 400. */
	private LocalDate checkWorkDate(UUID schoolId, LocalDate date) {
		LocalDate today = LocalDate.now(this.clock.withZone(zone(schoolId)));
		if (date == null) {
			return today;
		}
		if (date.isAfter(today)) {
			throw ApiException.badRequest("portfolio.work_date_invalid");
		}
		return date;
	}

	private ZoneId zone(UUID schoolId) {
		return this.countries.schoolZone(schoolId);
	}

	/** The class of a student's item: one they are ACTIVE in; when omitted, their only ACTIVE class (else none). */
	private UUID classOf(UUID studentId, UUID schoolId, UUID requested) {
		List<StudentClass> active = this.access.memberships().activeClassesOf(List.of(studentId)).getOrDefault(studentId, List.of());
		if (requested != null) {
			boolean member = active.stream().anyMatch((c) -> c.classId().equals(requested));
			boolean inSchool = this.access.classes().find(requested).filter((c) -> c.schoolId().equals(schoolId)).isPresent();
			if (!member || !inSchool) {
				throw ApiException.badRequest("portfolio.class_invalid");
			}
			return requested;
		}
		return (active.size() == 1) ? active.getFirst().classId() : null;
	}

	private void requireSubject(UUID schoolId, UUID subjectId) {
		if (subjectId == null) {
			return;
		}
		boolean ok = this.jdbc.sql("select exists (select 1 from subjects where id = :s and school_id = :school)")
			.param("s", subjectId)
			.param("school", schoolId)
			.query(Boolean.class)
			.single();
		if (!ok) {
			throw ApiException.badRequest("portfolio.subject_invalid");
		}
	}

	/** Other children who appear: ACTIVE students of the school, not the student themself, at most 20. */
	private Set<UUID> tags(UUID schoolId, UUID studentId, List<UUID> requested) {
		Set<UUID> tags = new LinkedHashSet<>(list(requested));
		tags.remove(studentId);
		if (tags.size() > MAX_TAGS) {
			throw ApiException.badRequest("portfolio.too_many_tags", MAX_TAGS);
		}
		if (!tags.isEmpty() && !this.access.people().activeStudents(schoolId, tags).containsAll(tags)) {
			throw ApiException.badRequest("portfolio.tag_invalid");
		}
		return tags;
	}

	/** A submitted or captured item must be complete: text for TEXT items, at least one file for the others. */
	private static void requireComplete(PortfolioItemType type, String text, int files) {
		if (type == PortfolioItemType.TEXT) {
			if (text == null) {
				throw ApiException.badRequest("portfolio.text_content_required");
			}
		}
		else if (files == 0) {
			throw ApiException.badRequest("portfolio.media_required");
		}
	}

	private UUID portfolioOf(UUID studentId, UUID schoolId) {
		this.jdbc.sql("insert into portfolios (student_id, school_id) values (:student, :school) on conflict (student_id, school_id) do nothing")
			.param("student", studentId)
			.param("school", schoolId)
			.update();
		return this.jdbc.sql("select id from portfolios where student_id = :student and school_id = :school")
			.param("student", studentId)
			.param("school", schoolId)
			.query(UUID.class)
			.single();
	}

	private void insertItem(UUID id, UUID portfolioId, UUID schoolId, UUID studentId, UUID classId, UUID subjectId, UUID createdBy,
			PortfolioItemType type, Fields f, PortfolioStatus status, PortfolioVisibility visibility, boolean showsOthers,
			UUID confirmedBy, Instant confirmedAt, Instant submittedAt) {
		this.jdbc.sql("""
				insert into portfolio_items (id, portfolio_id, school_id, student_id, class_id, subject_id, created_by, item_type, title,
				  description, reflection, text_content, work_date, status, visibility, shows_other_children, media_confirmed_by,
				  media_confirmed_at, submitted_at)
				values (:id, :portfolio, :school, :student, :class, :subject, :createdBy, :type, :title, :description, :reflection,
				  :text, :workDate, :status, :visibility, :others, :confirmedBy, :confirmedAt, :submittedAt)""")
			.param("id", id)
			.param("portfolio", portfolioId)
			.param("school", schoolId)
			.param("student", studentId)
			.param("class", classId)
			.param("subject", subjectId)
			.param("createdBy", createdBy)
			.param("type", type.name())
			.param("title", f.title())
			.param("description", f.description())
			.param("reflection", f.reflection())
			.param("text", f.textContent())
			.param("workDate", (f.workDate() != null) ? java.sql.Date.valueOf(f.workDate()) : null)
			.param("status", status.name())
			.param("visibility", visibility.name())
			.param("others", showsOthers)
			.param("confirmedBy", confirmedBy)
			.param("confirmedAt", (confirmedAt != null) ? Timestamp.from(confirmedAt) : null)
			.param("submittedAt", (submittedAt != null) ? Timestamp.from(submittedAt) : null)
			.update();
	}

	private void insertTags(UUID itemId, Set<UUID> tags) {
		for (UUID tag : tags) {
			this.jdbc.sql("insert into portfolio_item_tags (item_id, student_id) values (:i, :s)").param("i", itemId).param("s", tag).update();
		}
	}

	private void auditConfirmation(UUID itemId, UUID schoolId) {
		this.audit.record(AuditEntry.of("portfolio.item.media_confirmed", AuditOutcome.SUCCESS).target("portfolio_item", itemId).school(schoolId));
	}

}
