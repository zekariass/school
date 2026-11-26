package com.nemaz.school.portfolio.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.portfolio.PortfolioItemReviewed;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioDecision;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioReviewRequest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;
import com.nemaz.school.portfolio.application.PortfolioWorkflow.Move;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What reviewers do (rule book P2, P6): decide on submitted items, archive and unarchive, feature, and the (deferred) share to the
 * class story. Becoming visible (approval, unarchive) runs the consent gate. Every step is audited with ids and statuses only and
 * appends to the history; the reviewer's note is stored in the history and never in the audit log, events or notifications.
 */
@Service
public class PortfolioReviewService {

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

	private final Clock clock;

	PortfolioReviewService(JdbcClient jdbc, PortfolioAccess access, PortfolioAudience audience, PortfolioConsent consent,
			PortfolioMedia media, PortfolioViews views, PortfolioSettings settings, PortfolioLedger ledger, AuditLog audit,
			ApplicationEventPublisher events, Clock clock) {
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
		this.clock = clock;
	}

	@Transactional
	public PortfolioItemView review(CurrentUser user, UUID schoolId, UUID itemId, PortfolioReviewRequest req) {
		ItemRow item = reviewable(user, schoolId, itemId);
		item = lock(itemId);
		Move move = switch (req.decision()) {
			case APPROVE -> Move.APPROVE;
			case REQUEST_CHANGES -> Move.REQUEST_CHANGES;
			case REJECT -> Move.REJECT;
		};
		PortfolioStatus to = PortfolioWorkflow.require(item.status(), move);
		String note = (req.decision() == PortfolioDecision.APPROVE) ? PortfolioText.optional(req.note(), PortfolioText.NOTE_MAX, "note")
				: PortfolioText.required(req.note(), PortfolioText.NOTE_MAX, "note");
		PortfolioVisibility visibility = item.visibility();
		if (req.visibility() != null) {
			if (req.visibility().rank() > item.visibility().rank()) {
				throw ApiException.badRequest("portfolio.visibility_raise_not_allowed");
			}
			visibility = req.visibility();
		}
		if (to == PortfolioStatus.APPROVED) {
			runGate(user, item, visibility);
		}
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update portfolio_items set status = :status, visibility = :visibility, reviewed_by = :user, reviewed_at = :t,
				  updated_at = :t where id = :id""")
			.param("status", to.name())
			.param("visibility", visibility.name())
			.param("user", user.userId())
			.param("t", Timestamp.from(now))
			.param("id", itemId)
			.update();
		UUID approval = this.ledger.append(itemId, to.name(), item.status(), to, user.userId(), note);
		this.audit.record(AuditEntry.of("portfolio.item.review", AuditOutcome.SUCCESS)
			.target("portfolio_item", itemId)
			.school(schoolId)
			.metadata("decision", req.decision().name())
			.metadata("from", item.status().name())
			.metadata("to", to.name())
			.metadata("visibility", visibility.name())
			.metadata("visibilityLowered", visibility != item.visibility()));
		this.events.publishEvent(new PortfolioItemReviewed(itemId, schoolId, item.studentId(), approval, to.name(), false));
		return view(user, itemId);
	}

	/** Archives an APPROVED item: the student or a reviewer. Hides it from everybody but the student and the reviewers. */
	@Transactional
	public PortfolioItemView archive(CurrentUser user, UUID schoolId, UUID itemId, String rawNote) {
		this.access.requireSchool(user, schoolId);
		ItemRow item = visible(user, schoolId, itemId);
		boolean own = item.studentId().equals(user.userId());
		if (!own && !this.audience.reviews(user, itemId)) {
			throw ApiException.forbidden("portfolio.access_denied");
		}
		item = lock(itemId);
		PortfolioStatus to = PortfolioWorkflow.require(item.status(), Move.ARCHIVE);
		Instant now = this.clock.instant();
		this.jdbc.sql("""
				update portfolio_items set status = 'ARCHIVED', is_featured = false, archived_by = :user, archived_at = :t,
				  updated_at = :t where id = :id""")
			.param("user", user.userId())
			.param("t", Timestamp.from(now))
			.param("id", itemId)
			.update();
		this.ledger.append(itemId, "ARCHIVED", item.status(), to, user.userId(), PortfolioText.optional(rawNote, PortfolioText.NOTE_MAX, "note"));
		this.audit.record(AuditEntry.of("portfolio.item.archive", AuditOutcome.SUCCESS)
			.target("portfolio_item", itemId)
			.school(schoolId)
			.metadata("by", own ? "STUDENT" : "REVIEWER"));
		return view(user, itemId);
	}

	/** Brings an ARCHIVED item back (reviewers only); the consent gate runs again. */
	@Transactional
	public PortfolioItemView unarchive(CurrentUser user, UUID schoolId, UUID itemId, String rawNote) {
		ItemRow item = reviewable(user, schoolId, itemId);
		item = lock(itemId);
		PortfolioStatus to = PortfolioWorkflow.require(item.status(), Move.UNARCHIVE);
		runGate(user, item, item.visibility());
		this.jdbc.sql("""
				update portfolio_items set status = 'APPROVED', archived_by = null, archived_at = null, updated_at = :t where id = :id""")
			.param("t", Timestamp.from(this.clock.instant()))
			.param("id", itemId)
			.update();
		this.ledger.append(itemId, "UNARCHIVED", item.status(), to, user.userId(), PortfolioText.optional(rawNote, PortfolioText.NOTE_MAX, "note"));
		this.audit.record(AuditEntry.of("portfolio.item.unarchive", AuditOutcome.SUCCESS).target("portfolio_item", itemId).school(schoolId));
		return view(user, itemId);
	}

	/** Features or unfeatures an APPROVED item (reviewers); repeating the same setting changes nothing. */
	@Transactional
	public PortfolioItemView feature(CurrentUser user, UUID schoolId, UUID itemId, boolean featured) {
		ItemRow item = reviewable(user, schoolId, itemId);
		item = lock(itemId);
		if (item.status() != PortfolioStatus.APPROVED) {
			throw ApiException.conflict("portfolio.invalid_state");
		}
		if (item.featured() != featured) {
			this.jdbc.sql("update portfolio_items set is_featured = :f, updated_at = :t where id = :id")
				.param("f", featured)
				.param("t", Timestamp.from(this.clock.instant()))
				.param("id", itemId)
				.update();
			this.ledger.append(itemId, featured ? "FEATURED" : "UNFEATURED", item.status(), item.status(), user.userId(), null);
			this.audit.record(AuditEntry.of(featured ? "portfolio.item.feature" : "portfolio.item.unfeature", AuditOutcome.SUCCESS)
				.target("portfolio_item", itemId)
				.school(schoolId));
		}
		return view(user, itemId);
	}

	/**
	 * Sharing to the class story is deferred (rule book P6): it needs the item's files to become files of a post, and the media
	 * module has no copy operation. The checks run so the answer is the right one for the right person; then 422.
	 */
	@Transactional(readOnly = true)
	public void shareToStory(CurrentUser user, UUID schoolId, UUID itemId) {
		ItemRow item = reviewable(user, schoolId, itemId);
		if (item.status() != PortfolioStatus.APPROVED
				|| this.settings.effective(item.visibility(), schoolId).rank() < PortfolioVisibility.CLASS.rank()) {
			throw ApiException.conflict("portfolio.share_not_eligible");
		}
		throw ApiException.unprocessable("portfolio.share_unsupported");
	}

	// --- helpers -----------------------------------------------------------------------------------

	/** The consent gate for the item becoming visible with {@code visibility}. */
	private void runGate(CurrentUser user, ItemRow item, PortfolioVisibility visibility) {
		Set<UUID> tags = this.consent.tagsOf(java.util.List.of(item.id())).getOrDefault(item.id(), Set.of());
		boolean hasMedia = !this.media.mediaIdsOf(item.id()).isEmpty();
		boolean disclose = item.classId() != null && this.access.classes().canViewRoster(user, item.classId());
		this.consent.check(item.schoolId(), item.studentId(), item.showsOthers(), this.settings.effective(visibility, item.schoolId()),
				hasMedia, tags, item.hasConfirmation(), disclose);
	}

	/** The item as seen by a reviewer: 404 when not visible, 403 when visible but not reviewable by the caller. */
	private ItemRow reviewable(CurrentUser user, UUID schoolId, UUID itemId) {
		this.access.requireSchool(user, schoolId);
		ItemRow item = visible(user, schoolId, itemId);
		if (!this.audience.reviews(user, itemId)) {
			throw ApiException.forbidden("portfolio.review_not_allowed");
		}
		return item;
	}

	private ItemRow visible(CurrentUser user, UUID schoolId, UUID itemId) {
		return this.audience.find(user, itemId).filter((i) -> i.schoolId().equals(schoolId)).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
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

}
