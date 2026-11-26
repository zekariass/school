package com.nemaz.school.feed;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Parent and student contributions (spec §18): settings, tagging, the approval queue and the consent gate. */
@IntegrationTest
class ContributionTest extends FeedTestSupport {

	private JsonNode contribute(Persona who, Feed f, Map<String, Object> body, int status) throws Exception {
		return post(f.classApi() + "/contributions", who, body, status);
	}

	@Test
	void contributionsAreOffByDefaultAndCanBeSwitchedOnPerRole() throws Exception {
		Feed f = feed("contrib-off");
		assertThat(code(contribute(f.guardian1(), f, Map.of("body", "Hello"), 403))).isEqualTo("feed.contributions_disabled");
		assertThat(code(contribute(f.student1(), f, Map.of("body", "Hello"), 403))).isEqualTo("feed.contributions_disabled");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		assertThat(contribute(f.guardian1(), f, Map.of("body", "Hello"), 201).get("status").asString()).isEqualTo("PENDING_APPROVAL");
		// the student setting is separate
		assertThat(code(contribute(f.student1(), f, Map.of("body", "Hello"), 403))).isEqualTo("feed.contributions_disabled");
		setting(f.schoolId(), "feed.student_contributions.enabled", true);
		JsonNode student = contribute(f.student1(), f, Map.of("body", "My drawing"), 201);
		assertThat(student.get("status").asString()).isEqualTo("PENDING_APPROVAL");
		assertThat(student.get("author").get("role").asString()).isEqualTo("STUDENT");
		// capabilities tell the clients
		JsonNode caps = get(f.api() + "/feed/capabilities?classId=" + f.classId(), f.guardian1());
		assertThat(caps.get("canContribute").asBoolean()).isTrue();
		assertThat(caps.get("contributionNeedsApproval").asBoolean()).isTrue();
		assertThat(caps.get("contributionStudentIds")).hasSize(1);
		assertThat(caps.get("canPost").asBoolean()).isFalse();
		assertThat(get(f.api() + "/feed/capabilities?classId=" + f.classId(), f.lead()).get("canModerate").asBoolean()).isTrue();
	}

	@Test
	void onlyEligibleGuardiansAndStudentsOfTheClassContribute() throws Exception {
		Feed f = feed("contrib-who");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		setting(f.schoolId(), "feed.student_contributions.enabled", true);
		for (Persona outsider : List.of(f.restricted(), f.revoked(), f.otherGuardian(), f.otherStudent(), f.otherTeacher(),
				this.personas.newUser("contrib-stranger"))) {
			call(HttpMethod.POST, f.classApi() + "/contributions", outsider, Map.of("body", "Hi"), 404);
		}
		// a guardian who may not message the staff may not contribute
		this.jdbc.update("update student_guardians set can_message_staff = false where guardian_id = ?", f.guardian2().userId());
		assertThat(code(contribute(f.guardian2(), f, Map.of("body", "Hi"), 403))).isEqualTo("feed.contribution_not_allowed");
		// teachers use the post endpoint
		call(HttpMethod.POST, f.classApi() + "/contributions", f.lead(), Map.of("body", "Hi"), 403);
		// a guardian tags only their own child; a student tags nobody
		assertThat(code(contribute(f.guardian1(), f, Map.of("body", "Hi", "studentIds", List.of(f.s2().toString())), 400)))
			.isEqualTo("feed.contribution_tag_not_own");
		assertThat(code(contribute(f.student1(), f, Map.of("body", "Hi", "studentIds", List.of(f.s1().toString())), 400)))
			.isEqualTo("feed.contribution_tag_not_own");
		assertThat(contribute(f.guardian1(), f, Map.of("body", "Hi", "studentIds", List.of(f.s1().toString())), 201).get("tags")).hasSize(1);
		// empty contributions and unknown languages are refused
		assertThat(code(contribute(f.guardian1(), f, Map.of(), 400))).isEqualTo("feed.post_empty");
	}

	@Test
	void theApprovalQueueIsForTheLeadAndManagersAndTheFlowIsAudited() throws Exception {
		Feed f = feed("contrib-queue");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		JsonNode contribution = contribute(f.guardian1(), f, Map.of("body", "Abel's birthday cake"), 201);
		String id = contribution.get("id").asString();
		String queue = f.api() + "/contributions/pending?classId=" + f.classId();
		// who sees the queue
		for (Persona approver : List.of(f.lead(), f.admin())) {
			assertThat(ids(get(queue, approver))).as(approver.name()).containsExactly(id);
		}
		for (Persona other : List.of(f.co(), f.subject(), f.assistant(), f.guardian1(), f.student1())) {
			assertThat(code(call(HttpMethod.GET, queue, other, null, 403))).as(other.name()).isEqualTo("feed.access_denied");
		}
		call(HttpMethod.GET, queue, f.otherTeacher(), null, 404);
		call(HttpMethod.GET, queue, f.otherGuardian(), null, 404);
		// the school-wide queue shows the contributions of the classes the caller moderates
		assertThat(ids(get(f.api() + "/contributions/pending", f.lead()))).containsExactly(id);
		assertThat(ids(get(f.api() + "/contributions/pending", f.admin()))).containsExactly(id);
		assertThat(ids(get(f.api() + "/contributions/pending", f.otherTeacher()))).isEmpty();
		call(HttpMethod.GET, f.api() + "/contributions/pending", f.co(), null, 403);
		// pending contributions are in nobody's feed, and only the contributor and the moderators open them
		assertThat(ids(get(f.classApi() + "/feed", f.guardian2()))).isEmpty();
		call(HttpMethod.GET, f.api() + "/posts/" + id, f.guardian2(), null, 404);
		call(HttpMethod.GET, f.api() + "/posts/" + id, f.co(), null, 404);
		assertThat(get(f.api() + "/posts/" + id, f.guardian1()).get("status").asString()).isEqualTo("PENDING_APPROVAL");
		// the contributor may still change it while it waits
		assertThat(call(HttpMethod.PATCH, f.api() + "/posts/" + id, f.guardian1(), Map.of("body", "Abel's 7th birthday cake"), 200)
			.get("body").asString()).contains("7th");

		// deciding: a colleague who does not lead the class cannot; the lead can
		// (a pending contribution is invisible to them, so it is not found rather than forbidden)
		call(HttpMethod.POST, f.api() + "/contributions/" + id + "/approve", f.co(), Map.of(), 404);
		call(HttpMethod.POST, f.api() + "/contributions/" + id + "/reject", f.co(), Map.of("reason", "no"), 404);
		JsonNode approved = post(f.api() + "/contributions/" + id + "/approve", f.lead(), Map.of(), 200);
		assertThat(approved.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(ids(get(f.classApi() + "/feed", f.guardian2()))).containsExactly(id);
		assertThat(code(post(f.api() + "/contributions/" + id + "/approve", f.lead(), Map.of(), 409))).isEqualTo("feed.not_pending");
		assertThat(code(post(f.api() + "/contributions/" + id + "/reject", f.lead(), Map.of("reason", "late"), 409))).isEqualTo("feed.not_pending");
		// once approved it is the school's content: the contributor cannot rewrite it
		assertThat(code(call(HttpMethod.PATCH, f.api() + "/posts/" + id, f.guardian1(), Map.of("body", "Something else"), 409)))
			.isEqualTo("feed.invalid_state");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.contribution.approve' and target_id = ?",
				Integer.class, UUID.fromString(id))).isEqualTo(1);
		awaitNotifications(f.guardian1().userId(), "POST_APPROVED", 1);
		awaitNotifications(f.lead().userId(), "CONTRIBUTION_PENDING", 1);
		assertThat(notificationCount(f.co().userId(), "CONTRIBUTION_PENDING")).isZero();
	}

	@Test
	void rejectingNeedsAReasonDeletesThePhotosAndTellsTheContributor() throws Exception {
		Feed f = feed("contrib-reject");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		UUID photo = photo(f.guardian1(), f.classId());
		JsonNode contribution = contribute(f.guardian1(), f, Map.of("body", "Photo", "media", refs(photo)), 201);
		String id = contribution.get("id").asString();
		call(HttpMethod.POST, f.api() + "/contributions/" + id + "/reject", f.lead(), Map.of(), 400);
		call(HttpMethod.POST, f.api() + "/contributions/" + id + "/reject", f.lead(), Map.of("reason", " "), 400);
		JsonNode rejected = post(f.api() + "/contributions/" + id + "/reject", f.lead(), Map.of("reason", "Other children are visible"), 200);
		assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
		JsonNode mine = get(f.api() + "/my-posts?status=REJECTED", f.guardian1());
		assertThat(mine.get("items")).hasSize(1);
		assertThat(mine.get("items").get(0).get("rejectionReason").asString()).isEqualTo("Other children are visible");
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, photo)).isEqualTo("DELETED");
		awaitNotifications(f.guardian1().userId(), "POST_REJECTED", 1);
		// the reason is not in the push text
		assertThat(this.jdbc.queryForMap("select title, body from notifications where user_id = ? and type_code = 'POST_REJECTED'",
				f.guardian1().userId()).toString()).doesNotContain("Other children");
		assertThat(ids(get(f.classApi() + "/feed", f.guardian2()))).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.contribution.reject' and target_id = ?",
				Integer.class, UUID.fromString(id))).isEqualTo(1);
	}

	@Test
	void theContributorsNameIsKnownToStaffAndToThemselvesOnly() throws Exception {
		Feed f = feed("contrib-names");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		setting(f.schoolId(), "feed.student_contributions.enabled", true);
		setting(f.schoolId(), "feed.parent_contributions.approval_required", false);
		String byParent = contribute(f.guardian1(), f, Map.of("body", "From a parent"), 201).get("id").asString();
		String byStudent = contribute(f.student1(), f, Map.of("body", "From a student"), 201).get("id").asString();
		post(f.api() + "/contributions/" + byStudent + "/approve", f.lead(), Map.of(), 200);
		// a guardian contribution skipped approval; a student's never does
		assertThat(get(f.api() + "/posts/" + byParent, f.lead()).get("status").asString()).isEqualTo("PUBLISHED");
		for (Persona viewer : List.of(f.lead(), f.admin(), f.co())) {
			JsonNode author = get(f.api() + "/posts/" + byParent, viewer).get("author");
			assertThat(author.get("displayName").asString()).as(viewer.name()).isEqualTo(f.guardian1().name());
		}
		for (Persona viewer : List.of(f.guardian2(), f.student2(), f.student1())) {
			JsonNode author = get(f.api() + "/posts/" + byParent, viewer).get("author");
			assertThat(author.get("role").asString()).isEqualTo("GUARDIAN");
			assertThat(author.has("displayName")).as(viewer.name()).isFalse();
			assertThat(author.has("userId")).isFalse();
		}
		assertThat(get(f.api() + "/posts/" + byParent, f.guardian1()).get("author").get("displayName").asString()).isNotBlank();
		// a student's contribution is named to classmates and their own guardian, not to another family
		assertThat(get(f.api() + "/posts/" + byStudent, f.student2()).get("author").get("displayName").asString()).isEqualTo("Abel");
		assertThat(get(f.api() + "/posts/" + byStudent, f.guardian1()).get("author").get("displayName").asString()).isEqualTo("Abel");
		assertThat(get(f.api() + "/posts/" + byStudent, f.guardian2()).get("author").has("displayName")).isFalse();
		// the serialized post never carries a phone number
		assertThat(get(f.api() + "/posts/" + byParent, f.lead()).toString()).doesNotContain("+251");
	}

	@Test
	void withoutApprovalAPhotoMustShowTheContributorsOwnConsentedChild() throws Exception {
		Feed f = feed("contrib-noapproval");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		setting(f.schoolId(), "feed.parent_contributions.approval_required", false);
		UUID photo = photo(f.guardian1(), f.classId());
		// text goes straight to the feed
		assertThat(contribute(f.guardian1(), f, Map.of("body", "Hello class"), 201).get("status").asString()).isEqualTo("PUBLISHED");
		// a photo without a tagged child cannot skip approval
		assertThat(code(contribute(f.guardian1(), f, Map.of("media", refs(photo)), 400))).isEqualTo("feed.contribution_needs_tag");
		// tagged, but no consent yet
		assertThat(code(contribute(f.guardian1(), f, Map.of("media", refs(photo), "studentIds", List.of(f.s1().toString())), 422)))
			.isEqualTo("feed.media_consent_missing");
		grant(f.guardian1(), f.s1());
		JsonNode published = contribute(f.guardian1(), f, Map.of("media", refs(photo), "studentIds", List.of(f.s1().toString())), 201);
		assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
		// a student's contribution is always approved first, whatever the setting says
		setting(f.schoolId(), "feed.student_contributions.enabled", true);
		assertThat(contribute(f.student1(), f, Map.of("body", "Mine"), 201).get("status").asString()).isEqualTo("PENDING_APPROVAL");
	}

	@Test
	void theApproverConfirmsUntaggedPhotosOrTagsThem() throws Exception {
		Feed f = feed("contrib-approver");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		UUID photo = photo(f.guardian1(), f.classId());
		JsonNode pending = contribute(f.guardian1(), f, Map.of("media", refs(photo)), 201);
		String approve = f.api() + "/contributions/" + pending.get("id").asString() + "/approve";
		// the contributor's own "confirmation" does not exist: the approver must give it
		assertThat(code(post(approve, f.lead(), Map.of(), 422))).isEqualTo("feed.media_confirmation_required");
		// tagging a child without consent fails with the names
		JsonNode problem = post(approve, f.lead(), Map.of("studentIds", List.of(f.s2().toString())), 422);
		assertThat(code(problem)).isEqualTo("feed.media_consent_missing");
		assertThat(problem.get("students").get(0).get("displayName").asString()).isEqualTo("Bethlehem");
		JsonNode approved = post(approve, f.lead(), Map.of("confirmNoUnconsentedStudents", true), 200);
		assertThat(approved.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(this.jdbc.queryForObject("select media_confirmed_by from posts where id = ?", UUID.class,
				UUID.fromString(pending.get("id").asString()))).isEqualTo(f.lead().userId());
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.post.media_confirmed' and target_id = ?",
				Integer.class, UUID.fromString(pending.get("id").asString()))).isEqualTo(1);
	}

	@Test
	void theAllowedMediaSettingAndTheQueuePaging() throws Exception {
		Feed f = feed("contrib-paging");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		setting(f.schoolId(), "feed.parent_contributions.allowed_media", List.of("VIDEO"));
		UUID photo = photo(f.guardian1(), f.classId());
		assertThat(code(contribute(f.guardian1(), f, Map.of("media", refs(photo)), 403))).isEqualTo("feed.contribution_media_not_allowed");
		setting(f.schoolId(), "feed.parent_contributions.allowed_media", List.of("IMAGE"));
		List<String> created = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) {
			created.add(contribute(f.guardian1(), f, Map.of("body", "Entry " + i), 201).get("id").asString());
		}
		JsonNode first = get(f.api() + "/contributions/pending?limit=2", f.lead());
		assertThat(ids(first)).containsExactly(created.get(0), created.get(1));
		JsonNode second = get(f.api() + "/contributions/pending?limit=2&cursor=" + first.get("nextCursor").asString(), f.lead());
		assertThat(ids(second)).containsExactly(created.get(2));
		assertThat(second.has("nextCursor")).isFalse();
		// the contributor follows their own contributions
		assertThat(get(f.api() + "/my-posts", f.guardian1()).get("items")).hasSize(3);
		assertThat(get(f.api() + "/my-posts", f.guardian2()).get("items")).isEmpty();
	}

}
