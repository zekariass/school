package com.nemaz.school.engagement.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.engagement.PointTransactionType;
import com.nemaz.school.engagement.application.CategoryService.Category;
import com.nemaz.school.engagement.application.PointsLedger.AwarderRole;
import com.nemaz.school.engagement.application.PointsLedger.Row;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.system.RateLimiter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Awarding and reversing points: the only writers of the ledger (besides system awards through
 * {@link DefaultPointsApi}).
 * <ul>
 * <li><b>Who awards:</b> an ACTIVE teacher (or manager) of the class, to students ACTIVE in it, in a CLASS category of
 * the school or of the class; a points manager school-wide (SCHOOL context, school-level categories); a guardian only
 * for their own child through an ACTIVE, not custody-restricted link with {@code can_award_points}, in a HOME category
 * (context HOME, D2). Students never award. NEEDS_WORK categories only while the school allows them.</li>
 * <li><b>Idempotency:</b> {@code clientOpId} is the durable key of an offline queue: the same id (per awarder) returns
 * the first result and never awards twice, also for parallel calls (an advisory lock per operation plus a unique
 * index). A call spanning several students shares one {@code award_batch_id}.</li>
 * <li><b>Reversal:</b> a REVERSAL row (negative of the award, once per transaction). The awarder may undo within the
 * school's undo window; the class's lead teacher and points managers any time. HOME points are only ever undone by the
 * guardian who awarded them, inside the window.</li>
 * <li><b>Privacy:</b> the note is child data: stored in the ledger only, never logged, audited or put in events.</li>
 * </ul>
 */
@Service
@Transactional
public class AwardService {

	static final int MAX_STUDENTS_PER_AWARD = 500;

	private static final int HOME_AWARDS_PER_DAY = 60;

	private final PointsLedger ledger;

	private final CategoryService categories;

	private final EngagementAccess access;

	private final EngagementSettings settings;

	private final EngagementSeeding seeding;

	private final JdbcClient jdbc;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final Clock clock;

	AwardService(PointsLedger ledger, CategoryService categories, EngagementAccess access, EngagementSettings settings,
			EngagementSeeding seeding, JdbcClient jdbc, RateLimiter limiter,
			AuditLog audit, Clock clock) {
		this.ledger = ledger;
		this.categories = categories;
		this.access = access;
		this.settings = settings;
		this.seeding = seeding;
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.audit = audit;
		this.clock = clock;
	}

	// --- awards -----------------------------------------------------------------------------

	/** Awards to one, several or all ACTIVE students of a class (CLASS context). */
	public AwardResult awardInClass(CurrentUser user, UUID schoolId, UUID classId, AwardCommand cmd) {
		ClassRef ref = this.access.teachableClass(user, schoolId, classId);
		this.access.requireFeature(schoolId);
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		AwarderRole role = this.access.classes().membershipOf(user, classId).filter((m) -> m.kind() == Kind.TEACHER).isPresent()
				? AwarderRole.TEACHER : AwarderRole.SCHOOL_ADMIN;
		return once(user.userId(), cmd.clientOpId(), PointTransactionType.AWARD, () -> {
			Category category = usableCategory(schoolId, cmd.categoryId(), PointContext.CLASS, classId);
			Set<UUID> students = classStudents(schoolId, classId, cmd);
			return write(user, schoolId, PointContext.CLASS, classId, ref.academicYearId(), category, cmd, students, role);
		});
	}

	/** Awards school-wide (SCHOOL context) to ACTIVE students of the school; points managers only. */
	public AwardResult awardInSchool(CurrentUser user, UUID schoolId, AwardCommand cmd) {
		this.access.gate().require(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
		this.access.requireFeature(schoolId);
		return once(user.userId(), cmd.clientOpId(), PointTransactionType.AWARD, () -> {
			Category category = usableCategory(schoolId, cmd.categoryId(), PointContext.CLASS, null);
			if (cmd.studentIds() == null || cmd.studentIds().isEmpty()) {
				throw ApiException.conflict("points.no_students");
			}
			Set<UUID> students = new LinkedHashSet<>(cmd.studentIds());
			if (students.size() > MAX_STUDENTS_PER_AWARD) {
				throw ApiException.badRequest("points.too_many_students");
			}
			if (this.access.people().activeStudents(schoolId, students).size() != students.size()) {
				throw ApiException.conflict("points.student_not_in_school");
			}
			UUID year = this.access.currentYear(schoolId).orElseThrow(() -> ApiException.conflict("points.no_academic_year"));
			return write(user, schoolId, PointContext.SCHOOL, null, year, category, cmd, students, AwarderRole.SCHOOL_ADMIN);
		});
	}

	/** A guardian's home award to their own child (HOME context, HOME category). */
	public AwardResult awardAtHome(CurrentUser user, UUID schoolId, UUID studentId, AwardCommand cmd) {
		requireHomeAwarder(user, schoolId, studentId);
		this.access.requireFeature(schoolId);
		this.limiter.consume("points-home-award", user.userId().toString(), HOME_AWARDS_PER_DAY, Duration.ofDays(1),
				"points.rate_limited");
		return once(user.userId(), cmd.clientOpId(), PointTransactionType.AWARD, () -> {
			Category category = usableCategory(schoolId, cmd.categoryId(), PointContext.HOME, null);
			UUID year = this.access.currentYear(schoolId).orElseThrow(() -> ApiException.conflict("points.no_academic_year"));
			return write(user, schoolId, PointContext.HOME, null, year, category, cmd, Set.of(studentId), AwarderRole.GUARDIAN);
		});
	}

	/** Who may award at home: 404 for a stranger to the child, 403 for a guardian without {@code can_award_points}. */
	public void requireHomeAwarder(CurrentUser user, UUID schoolId, UUID studentId) {
		if (!this.access.activeStudentOfSchool(schoolId, studentId)
				|| !this.access.guardians().isActiveGuardian(user.userId(), studentId)) {
			throw ApiException.notFound("student.not_found");
		}
		if (!this.access.guardians().can(user.userId(), studentId, GuardianPermission.AWARD_POINTS)) {
			throw ApiException.forbidden("points.award_not_allowed");
		}
	}

	private Category usableCategory(UUID schoolId, UUID categoryId, PointContext context, UUID classId) {
		this.seeding.ensure(schoolId);
		Category category = this.categories.find(schoolId, categoryId)
			.filter((c) -> c.classId() == null || c.classId().equals(classId))
			.orElseThrow(() -> ApiException.notFound("points.category_not_found"));
		if (category.context() != context) {
			throw ApiException.conflict((context == PointContext.HOME) ? "points.category_not_home" : "points.category_is_home");
		}
		if (!category.active()) {
			throw ApiException.conflict("points.category_inactive");
		}
		if (category.polarity() == PointPolarity.NEEDS_WORK && !this.settings.needsWork(schoolId)) {
			throw ApiException.conflict("points.needs_work_disabled");
		}
		return category;
	}

	private Set<UUID> classStudents(UUID schoolId, UUID classId, AwardCommand cmd) {
		Set<UUID> active = this.access.memberships().activeStudentIds(classId);
		Set<UUID> students;
		if (Boolean.TRUE.equals(cmd.wholeClass())) {
			students = new LinkedHashSet<>(active);
		}
		else {
			if (cmd.studentIds() == null || cmd.studentIds().isEmpty()) {
				throw ApiException.conflict("points.no_students");
			}
			students = new LinkedHashSet<>(cmd.studentIds());
			if (students.size() > MAX_STUDENTS_PER_AWARD) {
				throw ApiException.badRequest("points.too_many_students");
			}
			if (!active.containsAll(students)) {
				throw ApiException.conflict("points.student_not_in_class");
			}
		}
		if (students.isEmpty()) {
			throw ApiException.conflict("points.no_students");
		}
		// A student enrolled in the class but no longer ACTIVE in the school gets nothing.
		Set<UUID> inSchool = this.access.people().activeStudents(schoolId, students);
		students.retainAll(inSchool);
		if (students.isEmpty()) {
			throw ApiException.conflict("points.no_students");
		}
		return students;
	}

	private AwardResult write(CurrentUser user, UUID schoolId, PointContext context, UUID classId, UUID yearId,
			Category category, AwardCommand cmd, Set<UUID> students, AwarderRole role) {
		int magnitude = (cmd.points() != null) ? cmd.points() : category.defaultPoints();
		if (magnitude < 1 || magnitude > 10) {
			throw ApiException.badRequest("points.invalid_points");
		}
		int signed = (category.polarity() == PointPolarity.POSITIVE) ? magnitude : -magnitude;
		UUID batchId = (students.size() > 1) ? Ids.newId() : null;
		Instant now = this.clock.instant();
		String note = (cmd.note() == null || cmd.note().isBlank()) ? null : cmd.note().strip();
		List<Row> rows = new ArrayList<>();
		for (UUID studentId : students) {
			Row row = new Row(Ids.newId(), schoolId, studentId, context, classId, yearId, category.id(), category.polarity(),
					signed, PointTransactionType.AWARD, null, note, user.userId(), role, batchId, cmd.clientOpId(), now);
			if (!this.ledger.append(row)) {
				throw ApiException.conflict("points.duplicate_operation");
			}
			rows.add(row);
		}
		this.audit.record(AuditEntry.of("points.award", AuditOutcome.SUCCESS)
			.target((batchId != null) ? "point_batch" : "point_transaction", (batchId != null) ? batchId : rows.getFirst().id())
			.school(schoolId)
			.metadata("context", context.name())
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("categoryId", category.id().toString())
			.metadata("points", signed)
			.metadata("students", rows.size()));
		return result(schoolId, rows, false);
	}

	// --- reversal ---------------------------------------------------------------------------

	/** Reverses one award (see the class comment for who may and when). */
	public AwardResult reverse(CurrentUser user, UUID schoolId, UUID transactionId, ReverseCommand cmd) {
		Row original = this.ledger.find(schoolId, transactionId).orElseThrow(() -> ApiException.notFound("points.transaction_not_found"));
		return reverseRows(user, schoolId, List.of(original), original, cmd);
	}

	/** Reverses every award of a whole-class or multi-select batch not reversed yet. */
	public AwardResult reverseBatch(CurrentUser user, UUID schoolId, UUID batchId, ReverseCommand cmd) {
		List<Row> awards = this.ledger.awardsOfBatch(schoolId, batchId);
		if (awards.isEmpty()) {
			throw ApiException.notFound("points.transaction_not_found");
		}
		return reverseRows(user, schoolId, awards, awards.getFirst(), cmd);
	}

	private AwardResult reverseRows(CurrentUser user, UUID schoolId, List<Row> targets, Row sample, ReverseCommand cmd) {
		AwarderRole role = authorizeReversal(user, schoolId, sample);
		return once(user.userId(), cmd.clientOpId(), PointTransactionType.REVERSAL, () -> {
			List<Row> reversals = new ArrayList<>();
			UUID batchId = null;
			Instant now = this.clock.instant();
			String note = (cmd.note() == null || cmd.note().isBlank()) ? null : cmd.note().strip();
			for (Row original : targets) {
				if (original.type() != PointTransactionType.AWARD) {
					throw ApiException.conflict("points.not_reversible");
				}
				if (this.ledger.isReversed(original.id())) {
					continue;
				}
				batchId = original.awardBatchId();
				Row reversal = new Row(Ids.newId(), original.schoolId(), original.studentId(), original.context(),
						original.classId(), original.academicYearId(), original.categoryId(), original.polarity(),
						-original.points(), PointTransactionType.REVERSAL, original.id(), note, user.userId(), role,
						original.awardBatchId(), cmd.clientOpId(), now);
				if (!this.ledger.append(reversal)) {
					// A parallel reversal won the unique index (or the operation id was used meanwhile).
					continue;
				}
				reversals.add(reversal);
			}
			if (reversals.isEmpty()) {
				throw ApiException.conflict("points.already_reversed");
			}
			this.audit.record(AuditEntry.of("points.reverse", AuditOutcome.SUCCESS)
				.target((batchId != null && targets.size() > 1) ? "point_batch" : "point_transaction",
						(batchId != null && targets.size() > 1) ? batchId : reversals.getFirst().reversesTransactionId())
				.school(schoolId)
				.metadata("context", sample.context().name())
				.metadata("classId", (sample.classId() != null) ? sample.classId().toString() : null)
				.metadata("transactions", reversals.size()));
			return result(schoolId, reversals, false);
		});
	}

	/**
	 * Decides who may reverse {@code sample}'s award and in which capacity. 404 when the caller has no business with
	 * the transaction, 403 when they may see it but not undo it.
	 */
	private AwarderRole authorizeReversal(CurrentUser user, UUID schoolId, Row sample) {
		boolean own = user.userId().equals(sample.awardedBy());
		boolean withinWindow = own && withinUndoWindow(schoolId, sample);
		if (sample.context() == PointContext.HOME) {
			// D2: home points are the family's; the school never reaches into them. Only the awarding guardian, and only briefly.
			if (!own) {
				throw ApiException.notFound("points.transaction_not_found");
			}
			if (!withinWindow) {
				throw ApiException.forbidden("points.undo_window_expired");
			}
			if (!this.access.guardians().can(user.userId(), sample.studentId(), GuardianPermission.AWARD_POINTS)) {
				throw ApiException.forbidden("points.award_not_allowed");
			}
			return AwarderRole.GUARDIAN;
		}
		boolean manager = this.access.pointsManager(user, schoolId);
		boolean teaches = sample.classId() != null && this.access.classes().canTeach(user, sample.classId());
		boolean leads = sample.classId() != null && this.access.classes().canLead(user, sample.classId());
		if (!manager && !teaches && !own) {
			throw ApiException.notFound("points.transaction_not_found");
		}
		boolean classTeacher = sample.classId() != null
				&& this.access.classes().membershipOf(user, sample.classId()).filter((m) -> m.kind() == Kind.TEACHER).isPresent();
		AwarderRole role = classTeacher ? AwarderRole.TEACHER : AwarderRole.SCHOOL_ADMIN;
		if (manager || leads) {
			return role;
		}
		if (own && teaches && withinWindow) {
			return role;
		}
		throw (own && teaches) ? ApiException.forbidden("points.undo_window_expired")
				: ApiException.forbidden("points.reversal_not_allowed");
	}

	private boolean withinUndoWindow(UUID schoolId, Row row) {
		int seconds = this.settings.undoWindowSeconds(schoolId);
		return seconds > 0 && !this.clock.instant().isAfter(row.createdAt().plusSeconds(seconds));
	}

	// --- system awards ----------------------------------------------------------------------

	/** A system award in the school's copy of a template category (see {@code PointsApi#awardAsSystem}). */
	UUID awardAsSystem(UUID schoolId, UUID studentId, UUID classId, String templateCode, UUID clientOpId) {
		if (clientOpId != null) {
			Locks.advisory(this.jdbc, "points-op:system:" + clientOpId);
			List<Row> existing = this.ledger.byOperation(null, clientOpId, PointTransactionType.AWARD);
			for (Row row : existing) {
				if (row.studentId().equals(studentId)) {
					return row.id();
				}
			}
		}
		Category category = this.categories.findTemplateCopy(schoolId, templateCode)
			.filter(Category::active)
			.orElseThrow(() -> ApiException.notFound("points.category_not_found"));
		if (category.context() != PointContext.CLASS || !this.access.activeStudentOfSchool(schoolId, studentId)) {
			throw ApiException.conflict("points.student_not_in_school");
		}
		UUID year;
		if (classId != null) {
			ClassRef ref = this.access.classes().find(classId).filter((c) -> c.schoolId().equals(schoolId))
				.orElseThrow(() -> ApiException.notFound("class.not_found"));
			if (!this.access.memberships().activeStudentIds(classId).contains(studentId)) {
				throw ApiException.conflict("points.student_not_in_class");
			}
			year = ref.academicYearId();
		}
		else {
			year = this.access.currentYear(schoolId).orElseThrow(() -> ApiException.conflict("points.no_academic_year"));
		}
		int signed = (category.polarity() == PointPolarity.POSITIVE) ? category.defaultPoints() : -category.defaultPoints();
		Row row = new Row(Ids.newId(), schoolId, studentId, (classId != null) ? PointContext.CLASS : PointContext.SCHOOL,
				classId, year, category.id(), category.polarity(), signed, PointTransactionType.AWARD, null, null, null,
				AwarderRole.SYSTEM, null, clientOpId, this.clock.instant());
		if (!this.ledger.append(row)) {
			throw ApiException.conflict("points.duplicate_operation");
		}
		return row.id();
	}

	// --- shared -----------------------------------------------------------------------------

	/** Runs {@code action} once per (awarder, operation id): the second call gets the first call's rows back. */
	private AwardResult once(UUID awarder, UUID clientOpId, PointTransactionType type, Supplier<AwardResult> action) {
		if (clientOpId == null) {
			return action.get();
		}
		Locks.advisory(this.jdbc, "points-op:" + awarder + ":" + clientOpId);
		List<Row> existing = this.ledger.byOperation(awarder, clientOpId, type);
		if (!existing.isEmpty()) {
			return result(existing.getFirst().schoolId(), existing, true);
		}
		return action.get();
	}

	private AwardResult result(UUID schoolId, List<Row> rows, boolean replayed) {
		Instant undoUntil = null;
		Row first = rows.getFirst();
		if (first.type() == PointTransactionType.AWARD) {
			int seconds = this.settings.undoWindowSeconds(schoolId);
			if (seconds > 0) {
				undoUntil = first.createdAt().plusSeconds(seconds);
			}
		}
		UUID batchId = first.awardBatchId();
		return new AwardResult(batchId, replayed, undoUntil,
				rows.stream().map(AwardService::view).toList());
	}

	static TransactionView view(Row r) {
		return new TransactionView(r.id(), r.studentId(), r.categoryId(), r.context(), r.classId(), r.polarity(), r.points(),
				r.type(), r.reversesTransactionId(), r.awardBatchId(), r.createdAt());
	}

	// --- shapes -----------------------------------------------------------------------------

	/**
	 * An award request. {@code studentIds} (one or several) or {@code wholeClass}; {@code points} defaults to the
	 * category's default (1 to 10; the sign comes from the category's polarity); {@code clientOpId} makes the call safe
	 * to repeat.
	 */
	public record AwardCommand(UUID categoryId, List<UUID> studentIds, Boolean wholeClass, Integer points, String note,
			UUID clientOpId) {

		@Override
		public String toString() {
			return "AwardCommand[redacted]";
		}

	}

	public record ReverseCommand(String note, UUID clientOpId) {

		@Override
		public String toString() {
			return "ReverseCommand[redacted]";
		}

	}

	public record TransactionView(@NotNull UUID id, @NotNull UUID studentId, @NotNull UUID categoryId,
			@NotNull PointContext context, UUID classId, @NotNull PointPolarity polarity,
			@Schema(description = "Signed: negative for needs-work awards and for the reversal of a positive award") int points,
			@NotNull PointTransactionType type, UUID reversesTransactionId, UUID awardBatchId, @NotNull Instant createdAt) {
	}

	public record AwardResult(@Schema(description = "Shared by the rows of a multi-student award; also the key to reverse the whole batch") UUID batchId,
			@Schema(description = "True when the client operation id was used before: these are the original rows, nothing new was written") boolean replayed,
			@Schema(description = "The awarder may undo until this instant (absent when the quick undo is off or for reversals)") Instant undoableUntil,
			@NotNull List<TransactionView> transactions) {
	}

}
