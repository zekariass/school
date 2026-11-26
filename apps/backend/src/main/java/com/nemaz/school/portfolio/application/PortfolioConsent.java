package com.nemaz.school.portfolio.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.consent.ConsentService;
import com.nemaz.school.consent.ConsentStatus;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;
import com.nemaz.school.portfolio.application.PortfolioModels.Withheld;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consent rules for children's media in portfolios (R-09, rule book P4), in one place.
 * <ul>
 * <li><b>When consent applies:</b> an item with files that is visible <em>beyond the student's own guardians</em> (effective
 * visibility CLASS or SCHOOL) or that shows other children ({@code shows_other_children}). Text-only items never need it.</li>
 * <li><b>The gate</b> ({@link #check}), run when an item becomes visible (approval, unarchive): the student and every tagged
 * child need MEDIA_PUBLICATION GRANTED (UNKNOWN counts as no); with no tagged child the creator must have confirmed that nobody
 * without consent appears.</li>
 * <li><b>Withholding</b> ({@link #withheld}), run on every read: the same test applied live. The reason decides who still sees the
 * files: when only the student's own consent is missing, the student's own guardians keep them; when another child (or the
 * missing confirmation about others) is the reason, only the student and the reviewers keep them.</li>
 * </ul>
 * Nothing is cached: {@link ConsentService} reads the database on every call, so a withdrawal applies at once.
 */
@Component
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class PortfolioConsent {

	private final ConsentService consent;

	private final JdbcClient jdbc;

	private final SchoolPeople people;

	private final PortfolioSettings settings;

	PortfolioConsent(ConsentService consent, JdbcClient jdbc, SchoolPeople people, PortfolioSettings settings) {
		this.consent = consent;
		this.jdbc = jdbc;
		this.people = people;
		this.settings = settings;
	}

	/** Whether consent applies to an item with files at this effective visibility. */
	static boolean applies(boolean hasMedia, PortfolioVisibility effective, boolean showsOthers) {
		return hasMedia && (effective.rank() >= PortfolioVisibility.CLASS.rank() || showsOthers);
	}

	/**
	 * The gate. {@code disclose}: whether the caller may be told the names of the children who lack consent (it sees the class
	 * roster); otherwise the error lists ids only.
	 *
	 * @throws ApiException 422 {@code portfolio.media_consent_missing} (property {@code students}) or 422
	 * {@code portfolio.media_confirmation_required}
	 */
	void check(UUID schoolId, UUID studentId, boolean showsOthers, PortfolioVisibility effective, boolean hasMedia,
			Collection<UUID> tags, boolean confirmed, boolean disclose) {
		if (!applies(hasMedia, effective, showsOthers)) {
			return;
		}
		Set<UUID> subjects = new LinkedHashSet<>();
		subjects.add(studentId);
		subjects.addAll(tags);
		List<UUID> lacking = lacking(schoolId, subjects);
		if (!lacking.isEmpty()) {
			Map<UUID, PersonCard> cards = disclose ? this.people.cards(lacking) : Map.of();
			List<Map<String, Object>> students = new ArrayList<>();
			for (UUID id : lacking) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("studentId", id);
				PersonCard card = cards.get(id);
				if (card != null) {
					entry.put("displayName", card.displayName());
				}
				students.add(entry);
			}
			throw ApiException.unprocessable("portfolio.media_consent_missing", lacking.size()).with("students", students);
		}
		if (tags.isEmpty() && !confirmed) {
			throw ApiException.unprocessable("portfolio.media_confirmation_required");
		}
	}

	/** The children whose MEDIA_PUBLICATION consent is not GRANTED (UNKNOWN counts as not granted). */
	List<UUID> lacking(UUID schoolId, Collection<UUID> students) {
		if (students.isEmpty()) {
			return List.of();
		}
		Map<UUID, ConsentStatus> statuses = this.consent.statuses(students, schoolId, ConsentType.MEDIA_PUBLICATION);
		List<UUID> lacking = new ArrayList<>();
		for (UUID id : new LinkedHashSet<>(students)) {
			ConsentStatus status = statuses.get(id);
			if (status == null || !status.granted()) {
				lacking.add(id);
			}
		}
		return lacking;
	}

	/** Why the files of each item are withheld right now (NONE for items without files or where consent holds). */
	Map<UUID, Withheld> withheld(Collection<ItemRow> items) {
		Map<UUID, Withheld> result = new HashMap<>();
		if (items.isEmpty()) {
			return result;
		}
		List<UUID> ids = items.stream().map(ItemRow::id).toList();
		Set<UUID> withMedia = new LinkedHashSet<>(this.jdbc
			.sql("select distinct item_id from portfolio_attachments where item_id in (:ids)")
			.param("ids", ids)
			.query(UUID.class)
			.list());
		Map<UUID, Set<UUID>> tags = tagsOf(ids);
		Map<UUID, Set<UUID>> subjectsBySchool = new HashMap<>();
		Map<UUID, PortfolioVisibility> effective = new HashMap<>();
		for (ItemRow item : items) {
			PortfolioVisibility e = this.settings.effective(item.visibility(), item.schoolId());
			effective.put(item.id(), e);
			if (applies(withMedia.contains(item.id()), e, item.showsOthers())) {
				Set<UUID> subjects = subjectsBySchool.computeIfAbsent(item.schoolId(), (k) -> new LinkedHashSet<>());
				subjects.add(item.studentId());
				subjects.addAll(tags.getOrDefault(item.id(), Set.of()));
			}
		}
		Map<UUID, Set<UUID>> lackingBySchool = new HashMap<>();
		subjectsBySchool.forEach((school, subjects) -> lackingBySchool.put(school, new LinkedHashSet<>(lacking(school, subjects))));
		for (ItemRow item : items) {
			if (!applies(withMedia.contains(item.id()), effective.get(item.id()), item.showsOthers())) {
				result.put(item.id(), Withheld.NONE);
				continue;
			}
			Set<UUID> lacking = lackingBySchool.get(item.schoolId());
			Set<UUID> itemTags = tags.getOrDefault(item.id(), Set.of());
			boolean othersLacking = itemTags.stream().anyMatch(lacking::contains) || (itemTags.isEmpty() && !item.hasConfirmation());
			boolean ownLacking = lacking.contains(item.studentId());
			result.put(item.id(), othersLacking ? Withheld.OTHERS : ownLacking ? Withheld.OWN_CONSENT : Withheld.NONE);
		}
		return result;
	}

	Withheld withheld(ItemRow item) {
		return withheld(List.of(item)).get(item.id());
	}

	Map<UUID, Set<UUID>> tagsOf(Collection<UUID> itemIds) {
		Map<UUID, Set<UUID>> tags = new HashMap<>();
		if (itemIds.isEmpty()) {
			return tags;
		}
		record Tag(UUID item, UUID student) {
		}
		this.jdbc.sql("select item_id, student_id from portfolio_item_tags where item_id in (:ids) order by item_id, student_id")
			.param("ids", itemIds)
			.query((rs, i) -> new Tag(rs.getObject("item_id", UUID.class), rs.getObject("student_id", UUID.class)))
			.list()
			.forEach((t) -> tags.computeIfAbsent(t.item(), (k) -> new LinkedHashSet<>()).add(t.student()));
		return tags;
	}

}
