package com.nemaz.school.feed.application;

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

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consent rules for children's media (R-09), in one place.
 * <ul>
 * <li><b>The gate</b> ({@link #check}), run when something with media becomes visible (publish, schedule, scheduler
 * run, approval, republish, edit of a published post): every tagged student needs MEDIA_PUBLICATION consent GRANTED;
 * with no tagged student the author must have confirmed that nobody without consent appears. Nothing is cached:
 * {@link ConsentService} reads the database on every call.</li>
 * <li><b>Withholding</b> ({@link #withheldPosts}, {@link #withheldStories}), run on every read of published content:
 * while any tagged student's consent is not GRANTED (it was withdrawn or refused after publishing, or the guardian link
 * that granted it ended) the media is hidden from everybody except the author and the moderators, and the media policy
 * refuses its signed URLs. When consent is granted again the media returns without any action.</li>
 * </ul>
 * Untagged media has no consent to re-check: it rests on the author's confirmation, which is recorded in the audit log.
 */
@Component
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
class ConsentWithholding {

	private final ConsentService consent;

	private final JdbcClient jdbc;

	private final SchoolPeople people;

	ConsentWithholding(ConsentService consent, JdbcClient jdbc, SchoolPeople people) {
		this.consent = consent;
		this.jdbc = jdbc;
		this.people = people;
	}

	/**
	 * The gate. {@code disclose} says whether the caller may be told the names of the students who lack consent (an
	 * author who may see the roster); otherwise the error lists ids only.
	 *
	 * @throws ApiException 422 {@code feed.media_consent_missing} (with the property {@code students}) or 422
	 * {@code feed.media_confirmation_required}
	 */
	void check(UUID schoolId, Collection<UUID> tags, boolean hasMedia, boolean confirmed, boolean disclose) {
		if (!hasMedia) {
			return;
		}
		if (tags.isEmpty()) {
			if (!confirmed) {
				throw ApiException.unprocessable("feed.media_confirmation_required");
			}
			return;
		}
		List<UUID> lacking = lacking(schoolId, tags);
		if (lacking.isEmpty()) {
			return;
		}
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
		throw ApiException.unprocessable("feed.media_consent_missing", lacking.size()).with("students", students);
	}

	/** The tagged students whose MEDIA_PUBLICATION consent is not GRANTED (UNKNOWN counts as not granted). */
	List<UUID> lacking(UUID schoolId, Collection<UUID> tags) {
		if (tags.isEmpty()) {
			return List.of();
		}
		Map<UUID, ConsentStatus> statuses = this.consent.statuses(tags, schoolId, ConsentType.MEDIA_PUBLICATION);
		List<UUID> lacking = new ArrayList<>();
		for (UUID id : new LinkedHashSet<>(tags)) {
			ConsentStatus status = statuses.get(id);
			if (status == null || !status.granted()) {
				lacking.add(id);
			}
		}
		return lacking;
	}

	/** Which of the posts have a tagged student without GRANTED consent now. */
	Set<UUID> withheldPosts(Collection<UUID> postIds) {
		return withheld(postIds, "post_student_tags", "post_id", "posts", "id");
	}

	Set<UUID> withheldStories(Collection<UUID> storyIds) {
		return withheld(storyIds, "story_student_tags", "story_id", "stories", "id");
	}

	boolean postWithheld(UUID postId) {
		return !withheldPosts(List.of(postId)).isEmpty();
	}

	boolean storyWithheld(UUID storyId) {
		return !withheldStories(List.of(storyId)).isEmpty();
	}

	private Set<UUID> withheld(Collection<UUID> ids, String tagTable, String tagColumn, String ownerTable,
			String ownerKey) {
		if (ids.isEmpty()) {
			return Set.of();
		}
		record TagRow(UUID owner, UUID schoolId, UUID student) {
		}
		List<TagRow> rows = this.jdbc.sql("select t." + tagColumn + " as owner_id, o.school_id, t.student_id from " + tagTable
				+ " t join " + ownerTable + " o on o." + ownerKey + " = t." + tagColumn + " where t." + tagColumn
				+ " in (:ids)")
			.param("ids", ids)
			.query((rs, i) -> new TagRow(rs.getObject("owner_id", UUID.class), rs.getObject("school_id", UUID.class),
					rs.getObject("student_id", UUID.class)))
			.list();
		Map<UUID, Set<UUID>> studentsBySchool = new HashMap<>();
		for (TagRow row : rows) {
			studentsBySchool.computeIfAbsent(row.schoolId(), (k) -> new LinkedHashSet<>()).add(row.student());
		}
		Map<UUID, Set<UUID>> notGrantedBySchool = new HashMap<>();
		studentsBySchool.forEach((school, students) -> notGrantedBySchool.put(school,
				new LinkedHashSet<>(lacking(school, students))));
		Set<UUID> withheld = new LinkedHashSet<>();
		for (TagRow row : rows) {
			if (notGrantedBySchool.get(row.schoolId()).contains(row.student())) {
				withheld.add(row.owner());
			}
		}
		return withheld;
	}

}
