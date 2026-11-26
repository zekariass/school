package com.nemaz.school.portfolio.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioAuthorRole;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemCapabilities;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioItemView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioMediaView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStudentView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioTaggedStudent;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;
import com.nemaz.school.portfolio.application.PortfolioModels.Withheld;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds what a viewer is shown of items (rule book P4 to P7): the viewer's relation to each item (the student, a reviewer, the
 * student's own guardian, or just part of the audience) decides the files (withholding), the review note, the tagged children, the
 * comment count and the capabilities. Students appear with BASIC data only. Everything is computed per request.
 */
@Component
@Transactional(readOnly = true)
public class PortfolioViews {

	private final PortfolioAudience audience;

	private final PortfolioAccess access;

	private final PortfolioConsent consent;

	private final PortfolioMedia media;

	private final PortfolioSettings settings;

	private final JdbcClient jdbc;

	PortfolioViews(PortfolioAudience audience, PortfolioAccess access, PortfolioConsent consent, PortfolioMedia media,
			PortfolioSettings settings, JdbcClient jdbc) {
		this.audience = audience;
		this.access = access;
		this.consent = consent;
		this.media = media;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	/** The viewer's relation to an item. */
	record Relation(boolean self, boolean reviewer, boolean ownGuardian) {

		boolean readsComments() {
			return this.self || this.reviewer || this.ownGuardian;
		}

	}

	List<Relation> relations(CurrentUser user, List<ItemRow> items) {
		Set<UUID> reviewed = this.audience.reviewedAmong(user, items.stream().map(ItemRow::id).toList());
		Set<UUID> guarded = this.access.guardedStudents(user);
		List<Relation> result = new ArrayList<>();
		for (ItemRow item : items) {
			result.add(new Relation(item.studentId().equals(user.userId()), reviewed.contains(item.id()),
					guarded.contains(item.studentId())));
		}
		return result;
	}

	PortfolioItemView view(CurrentUser user, ItemRow item) {
		return views(user, List.of(item)).getFirst();
	}

	List<PortfolioItemView> views(CurrentUser user, List<ItemRow> items) {
		if (items.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = items.stream().map(ItemRow::id).toList();
		List<Relation> relations = relations(user, items);
		Map<UUID, Withheld> withheld = this.consent.withheld(items);
		Map<UUID, List<PortfolioMediaView>> files = this.media.viewsOf(ids);
		Set<UUID> hasFiles = this.media.itemsWithFiles(ids);
		Map<UUID, Set<UUID>> tags = this.consent.tagsOf(ids);
		Set<UUID> peopleIds = new java.util.LinkedHashSet<>();
		items.forEach((i) -> peopleIds.add(i.studentId()));
		tags.values().forEach(peopleIds::addAll);
		Map<UUID, PersonCard> cards = this.access.people().cards(peopleIds);
		Map<UUID, String> notes = latestNotes(ids);
		Map<UUID, Integer> comments = commentCounts(ids);
		List<PortfolioItemView> views = new ArrayList<>();
		for (int n = 0; n < items.size(); n++) {
			ItemRow item = items.get(n);
			Relation rel = relations.get(n);
			Withheld reason = withheld.getOrDefault(item.id(), Withheld.NONE);
			boolean hidden = reason != Withheld.NONE && !rel.self() && !rel.reviewer()
					&& !(reason == Withheld.OWN_CONSENT && rel.ownGuardian());
			PortfolioVisibility effective = this.settings.effective(item.visibility(), item.schoolId());
			List<PortfolioMediaView> shown = hidden ? List.of() : files.getOrDefault(item.id(), List.of());
			Set<UUID> itemTags = tags.getOrDefault(item.id(), Set.of());
			boolean staffSide = rel.self() || rel.reviewer();
			List<PortfolioTaggedStudent> tagged = null;
			if (staffSide) {
				tagged = new ArrayList<>();
				for (UUID tag : itemTags) {
					PersonCard card = cards.get(tag);
					tagged.add(new PortfolioTaggedStudent(tag, (card != null) ? card.displayName() : null));
				}
			}
			PersonCard card = cards.get(item.studentId());
			PortfolioStudentView student = new PortfolioStudentView(item.studentId(), (card != null) ? card.displayName() : null,
					(card != null) ? card.avatar() : null, (card != null) ? card.avatarConfig() : null);
			boolean mediaWithheld = hasFiles.contains(item.id())
					&& (hidden || (staffSide && reason != Withheld.NONE));
			views.add(new PortfolioItemView(item.id(), item.schoolId(), student, item.classId(), item.subjectId(), item.itemType(),
					item.title(), item.description(), item.reflection(), item.textContent(), item.workDate(), item.status(),
					item.visibility(), effective, item.featured(),
					item.createdBy().equals(item.studentId()) ? PortfolioAuthorRole.STUDENT : PortfolioAuthorRole.TEACHER,
					item.createdAt(), item.submittedAt(), item.reviewedAt(), shown, mediaWithheld, item.showsOthers(),
					item.hasConfirmation(), itemTags.size(), tagged, staffSide ? notes.get(item.id()) : null,
					rel.readsComments() ? comments.getOrDefault(item.id(), 0) : 0, capabilities(user, item, rel, effective)));
		}
		return views;
	}

	PortfolioItemCapabilities capabilities(CurrentUser user, ItemRow item, Relation rel, PortfolioVisibility effective) {
		PortfolioStatus s = item.status();
		boolean studentWrites = rel.self() && item.createdBy().equals(item.studentId()) && this.settings.studentCreate(item.schoolId());
		boolean editable = studentWrites && (s == PortfolioStatus.DRAFT || s == PortfolioStatus.CHANGES_REQUESTED);
		boolean delete = (studentWrites && (s == PortfolioStatus.DRAFT || s == PortfolioStatus.SUBMITTED
				|| s == PortfolioStatus.CHANGES_REQUESTED)) || (rel.reviewer() && s != PortfolioStatus.DRAFT);
		boolean guardianVisible = effective.rank() >= PortfolioVisibility.GUARDIANS.rank();
		boolean comment = s == PortfolioStatus.APPROVED && guardianVisible && (rel.self() || rel.reviewer()
				|| (rel.ownGuardian() && this.settings.guardianComments(item.schoolId())));
		return new PortfolioItemCapabilities(editable, editable, delete, rel.reviewer() && s == PortfolioStatus.SUBMITTED,
				(rel.self() || rel.reviewer()) && s == PortfolioStatus.APPROVED, rel.reviewer() && s == PortfolioStatus.ARCHIVED,
				rel.reviewer() && s == PortfolioStatus.APPROVED, comment, false);
	}

	private Map<UUID, String> latestNotes(Collection<UUID> ids) {
		Map<UUID, String> notes = new HashMap<>();
		record Note(UUID item, String note) {
		}
		this.jdbc.sql("""
				select distinct on (item_id) item_id, note from portfolio_approvals
				where item_id in (:ids) and action in ('APPROVED', 'CHANGES_REQUESTED', 'REJECTED') and note is not null
				order by item_id, id desc""")
			.param("ids", ids)
			.query((rs, i) -> new Note(Rows.uuid(rs, "item_id"), rs.getString("note")))
			.list()
			.forEach((n) -> notes.put(n.item(), n.note()));
		return notes;
	}

	private Map<UUID, Integer> commentCounts(Collection<UUID> ids) {
		Map<UUID, Integer> counts = new HashMap<>();
		record Count(UUID item, int n) {
		}
		this.jdbc.sql("select item_id, count(*) as n from portfolio_comments where item_id in (:ids) and status = 'VISIBLE' group by item_id")
			.param("ids", ids)
			.query((rs, i) -> new Count(Rows.uuid(rs, "item_id"), rs.getInt("n")))
			.list()
			.forEach((c) -> counts.put(c.item(), c.n()));
		return counts;
	}

}
