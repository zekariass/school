package com.nemaz.school.feed;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.consent.ConsentChanged;
import com.nemaz.school.consent.ConsentType;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The consent gate of R-09 and what happens when consent changes: publish, schedule, scheduler re-check, withdrawal
 * hiding, editing a published post.
 */
@IntegrationTest
class ConsentGateTest extends FeedTestSupport {

	@Autowired
	ApplicationEventPublisher publisher;

	private JsonNode publishedPhotoPost(Feed f, UUID... tags) throws Exception {
		return photoPost(f.lead(), f, "Trip to the museum", tags);
	}

	@Test
	void taggedStudentsWithoutConsentBlockPublishingAndAreNamedToTheAuthorOnly() throws Exception {
		Feed f = feed("gate-tagged");
		UUID photo = photo(f.lead(), f.classId());
		Map<String, Object> extra = Map.of("media", refs(photo), "studentIds", List.of(f.s1().toString(), f.s2().toString()));
		JsonNode problem = classPost(f.lead(), f, "Museum", 422, extra);
		assertThat(code(problem)).isEqualTo("feed.media_consent_missing");
		assertThat(problem.get("students")).hasSize(2);
		assertThat(problem.get("students").findValuesAsString("displayName")).containsExactlyInAnyOrder("Abel", "Bethlehem");
		assertThat(problem.get("detail").asString()).contains("2 tagged student");
		// nothing was created, and the photo stays an unattached upload
		assertThat(this.jdbc.queryForObject("select count(*) from posts where school_id = ?", Integer.class, f.schoolId()))
			.isZero();

		grant(f.guardian1(), f.s1());
		problem = classPost(f.lead(), f, "Museum", 422, extra);
		assertThat(problem.get("students")).hasSize(1);
		assertThat(problem.get("students").get(0).get("displayName").asString()).isEqualTo("Bethlehem");

		grant(f.guardian2(), f.s2());
		JsonNode post = classPost(f.lead(), f, "Museum", 201, extra);
		assertThat(post.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(post.get("media")).hasSize(1);
		assertThat(post.get("tags")).hasSize(2);
	}

	@Test
	void aDenialOrAnUnknownDecisionBothBlock() throws Exception {
		Feed f = feed("gate-denied");
		decide(f.guardian1(), f.s1(), "DENIED");
		UUID photo = photo(f.lead(), f.classId());
		JsonNode problem = classPost(f.lead(), f, "x", 422, Map.of("media", refs(photo), "studentIds", List.of(f.s1().toString())));
		assertThat(code(problem)).isEqualTo("feed.media_consent_missing");
		// text-only posts never need consent, even tagging a refused child
		JsonNode text = classPost(f.lead(), f, "Well done everybody", 201, Map.of("studentIds", List.of(f.s1().toString())));
		assertThat(text.get("status").asString()).isEqualTo("PUBLISHED");
	}

	@Test
	void untaggedPhotosNeedTheTeachersConfirmationWhichIsAudited() throws Exception {
		Feed f = feed("gate-untagged");
		UUID photo = photo(f.lead(), f.classId());
		JsonNode problem = classPost(f.lead(), f, "Playground", 422, Map.of("media", refs(photo)));
		assertThat(code(problem)).isEqualTo("feed.media_confirmation_required");
		JsonNode post = classPost(f.lead(), f, "Playground", 201,
				Map.of("media", refs(photo), "confirmNoUnconsentedStudents", true));
		assertThat(post.get("status").asString()).isEqualTo("PUBLISHED");
		UUID id = UUID.fromString(post.get("id").asString());
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.post.media_confirmed' and target_id = ?",
				Integer.class, id)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select media_confirmed_by from posts where id = ?", UUID.class, id))
			.isEqualTo(f.lead().userId());
	}

	@Test
	void draftsSkipTheGateAndPublishingRunsIt() throws Exception {
		Feed f = feed("gate-draft");
		UUID photo = photo(f.lead(), f.classId());
		JsonNode draft = classPost(f.lead(), f, "Draft", 201, Map.of("mode", "DRAFT", "media", refs(photo), "studentIds",
				List.of(f.s1().toString())));
		assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
		String url = f.api() + "/posts/" + draft.get("id").asString();
		assertThat(code(post(url + "/publish", f.lead(), null, 422))).isEqualTo("feed.media_consent_missing");
		grant(f.guardian1(), f.s1());
		JsonNode published = post(url + "/publish", f.lead(), null, 200);
		assertThat(published.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(published.get("publishedAt").isNull()).isFalse();
	}

	@Test
	void schedulingRunsTheGateNowAndTheSchedulerRunsItAgain() throws Exception {
		Feed f = feed("gate-schedule");
		grant(f.guardian1(), f.s1());
		UUID photo = photo(f.lead(), f.classId());
		String at = this.clock.instant().plus(Duration.ofHours(1)).toString();
		JsonNode scheduled = classPost(f.lead(), f, "Tomorrow", 201, Map.of("mode", "SCHEDULE", "scheduledFor", at, "media",
				refs(photo), "studentIds", List.of(f.s1().toString())));
		assertThat(scheduled.get("status").asString()).isEqualTo("SCHEDULED");
		String url = f.api() + "/posts/" + scheduled.get("id").asString();
		// not due yet: nothing happens, and it is invisible to families
		assertThat(this.maintenance.publishDue()).isZero();
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).doesNotContain(scheduled.get("id").asString());

		// the guardian withdraws consent before the time comes
		decide(f.guardian1(), f.s1(), "WITHDRAWN");
		this.clock.advance(Duration.ofHours(2));
		assertThat(this.maintenance.publishDue()).isZero();
		JsonNode blocked = get(url, f.lead());
		assertThat(blocked.get("status").asString()).isEqualTo("DRAFT");
		assertThat(blocked.get("publishErrorCode").asString()).isEqualTo("feed.media_consent_missing");
		awaitNotifications(f.lead().userId(), "POST_PUBLISH_BLOCKED", 1);
		// repeated runs change nothing and do not notify again
		assertThat(this.maintenance.publishDue()).isZero();
		assertThat(this.maintenance.publishDue()).isZero();
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(notificationCount(f.lead().userId(), "POST_PUBLISH_BLOCKED")).isEqualTo(1));
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).doesNotContain(scheduled.get("id").asString());
	}

	@Test
	void theSchedulerPublishesDuePostsOnceAndNotifiesTheTaggedGuardians() throws Exception {
		Feed f = feed("gate-scheduler");
		grant(f.guardian1(), f.s1());
		UUID photo = photo(f.lead(), f.classId());
		String at = this.clock.instant().plus(Duration.ofMinutes(30)).toString();
		JsonNode scheduled = classPost(f.lead(), f, "Scheduled", 201, Map.of("mode", "SCHEDULE", "scheduledFor", at, "media",
				refs(photo), "studentIds", List.of(f.s1().toString())));
		this.clock.advance(Duration.ofHours(1));
		assertThat(this.maintenance.publishDue()).isEqualTo(1);
		assertThat(this.maintenance.publishDue()).isZero();
		JsonNode post = get(f.api() + "/posts/" + scheduled.get("id").asString(), f.guardian1());
		assertThat(post.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(post.get("media")).hasSize(1);
		awaitNotifications(f.guardian1().userId(), "NEW_POST", 1);
		// the event is delivered at least once: a second delivery sends nothing
		UUID postId = UUID.fromString(scheduled.get("id").asString());
		this.tx.executeWithoutResult((status) -> this.publisher.publishEvent(new PostPublished(postId, f.schoolId(), f.classId(),
				f.lead().userId(), false)));
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
			.untilAsserted(() -> assertThat(notificationCount(f.guardian1().userId(), "NEW_POST")).isEqualTo(1));
		// the other guardian (child not tagged) and the author are not notified (feed.notify_class_members is off)
		assertThat(notificationCount(f.guardian2().userId(), "NEW_POST")).isZero();
		assertThat(notificationCount(f.lead().userId(), "NEW_POST")).isZero();
		// the notification text carries neither the post text nor the child's name
		Map<String, Object> notification = this.jdbc.queryForMap("select title, body from notifications where user_id = ? and type_code = 'NEW_POST'",
				f.guardian1().userId());
		assertThat(notification.toString()).doesNotContain("Scheduled").doesNotContain("Abel");
	}

	@Test
	void aSchedulerRunAfterTheAuthorTookTheScheduleBackDoesNothing() throws Exception {
		Feed f = feed("gate-unschedule");
		String at = this.clock.instant().plus(Duration.ofMinutes(10)).toString();
		JsonNode scheduled = classPost(f.lead(), f, "Text", 201, Map.of("mode", "SCHEDULE", "scheduledFor", at));
		post(f.api() + "/posts/" + scheduled.get("id").asString() + "/unschedule", f.lead(), null, 200);
		this.clock.advance(Duration.ofHours(1));
		assertThat(this.maintenance.publishDue()).isZero();
		assertThat(get(f.api() + "/posts/" + scheduled.get("id").asString(), f.lead()).get("status").asString())
			.isEqualTo("DRAFT");
	}

	@Test
	void withdrawingConsentHidesThePhotosFromFamiliesButNotFromTheAuthorAndModerators() throws Exception {
		Feed f = feed("gate-withdraw");
		grant(f.guardian1(), f.s1());
		JsonNode post = publishedPhotoPost(f, f.s1());
		String id = post.get("id").asString();
		String media = post.get("media").get(0).get("mediaId").asString();
		String url = f.api() + "/posts/" + id;
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(200);
		assertThat(mediaUrlStatus(f.student1(), media)).isEqualTo(200);
		assertThat(mediaUrlStatus(f.guardian2(), media)).isEqualTo(200);

		decide(f.guardian1(), f.s1(), "WITHDRAWN");

		for (Persona viewer : List.of(f.guardian1(), f.student1(), f.student2(), f.guardian2(), f.subject(), f.co())) {
			JsonNode seen = get(url, viewer);
			assertThat(seen.get("media")).as(viewer.name()).isEmpty();
			assertThat(seen.get("mediaWithheld").asBoolean()).as(viewer.name()).isTrue();
			assertThat(seen.get("body").asString()).isEqualTo("Trip to the museum");
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
			JsonNode feedPage = get(f.classApi() + "/feed", viewer);
			assertThat(feedPage.get("items").get(0).get("media")).as("feed " + viewer.name()).isEmpty();
		}
		// the home feed of a guardian respects it too
		assertThat(get("/api/v1/me/feed", f.guardian1()).get("items").get(0).get("media")).isEmpty();
		// the author and the moderators still see and open the photo, with the warning flag
		for (Persona privileged : List.of(f.lead(), f.admin())) {
			JsonNode seen = get(url, privileged);
			assertThat(seen.get("media")).as(privileged.name()).hasSize(1);
			assertThat(seen.get("mediaWithheld").asBoolean()).isTrue();
			assertThat(mediaUrlStatus(privileged, media)).as(privileged.name()).isEqualTo(200);
		}
		// the author is told, once
		awaitNotifications(f.lead().userId(), "POST_MEDIA_WITHHELD", 1);
		UUID record = this.jdbc.queryForObject("select id from consent_records where decision = 'WITHDRAWN' and subject_user_id = ?",
				UUID.class, f.s1());
		redeliver(f, record);
		redeliver(f, record);
		await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
			.untilAsserted(() -> assertThat(notificationCount(f.lead().userId(), "POST_MEDIA_WITHHELD")).isEqualTo(1));

		// consent comes back: the photos return by themselves
		grant(f.guardian1(), f.s1());
		JsonNode back = get(url, f.guardian1());
		assertThat(back.get("media")).hasSize(1);
		assertThat(back.get("mediaWithheld").asBoolean()).isFalse();
		assertThat(mediaUrlStatus(f.guardian1(), media)).isEqualTo(200);
	}

	/** Delivers the same consent event again (the outbox delivers at least once). */
	private void redeliver(Feed f, UUID record) {
		this.tx.executeWithoutResult((status) -> this.publisher.publishEvent(new ConsentChanged(record, f.s1(), f.schoolId(),
				ConsentType.MEDIA_PUBLICATION, "WITHDRAWN", f.guardian1().userId())));
	}

	@Test
	void aSecondGuardiansRefusalHidesThePhotosToo() throws Exception {
		Feed f = feed("gate-two-guardians");
		Persona father = this.personas.newUser("father");
		this.personas.linkGuardian(f.s1(), father.userId(), "ACTIVE", false);
		grant(f.guardian1(), f.s1());
		JsonNode post = publishedPhotoPost(f, f.s1());
		String media = post.get("media").get(0).get("mediaId").asString();
		assertThat(mediaUrlStatus(f.student2(), media)).isEqualTo(200);
		decide(father, f.s1(), "DENIED");
		assertThat(mediaUrlStatus(f.student2(), media)).isEqualTo(404);
	}

	@Test
	void editingAPublishedPostCannotSmuggleInUnconsentedStudentsOrPhotos() throws Exception {
		Feed f = feed("gate-edit");
		grant(f.guardian1(), f.s1());
		JsonNode post = publishedPhotoPost(f, f.s1());
		String url = f.api() + "/posts/" + post.get("id").asString();
		JsonNode edited = call(HttpMethod.PATCH, url, f.lead(), Map.of("body", "Updated text"), 200);
		assertThat(edited.get("editedAt").isNull()).isFalse();
		assertThat(edited.get("body").asString()).isEqualTo("Updated text");
		// tagging a child without consent
		assertThat(code(call(HttpMethod.PATCH, url, f.lead(), Map.of("studentIds", List.of(f.s1().toString(), f.s2().toString())), 422)))
			.isEqualTo("feed.media_consent_missing");
		// removing every tag leaves untagged photos: confirmation needed again
		assertThat(code(call(HttpMethod.PATCH, url, f.lead(), Map.of("studentIds", List.of()), 422)))
			.isEqualTo("feed.media_confirmation_required");
		JsonNode untagged = call(HttpMethod.PATCH, url, f.lead(), Map.of("studentIds", List.of(), "confirmNoUnconsentedStudents", true), 200);
		assertThat(untagged.get("tags")).isEmpty();
		// a new photo on a tagged post
		UUID extra = photo(f.lead(), f.classId());
		assertThat(code(call(HttpMethod.PATCH, url, f.lead(), Map.of("media", refs(extra), "studentIds", List.of(f.s2().toString())), 422)))
			.isEqualTo("feed.media_consent_missing");
	}

	@Test
	void schoolWidePostsUseTheSameGateAndNameTheTaggedOnlyToThoseWhoMayKnowThem() throws Exception {
		Feed f = feed("gate-school");
		UUID photo = schoolPhoto(f.admin(), f.schoolId());
		Map<String, Object> body = Map.of("body", "Open day", "media", refs(photo), "studentIds", List.of(f.s1().toString()));
		JsonNode problem = post(f.api() + "/posts", f.admin(), body, 422);
		assertThat(code(problem)).isEqualTo("feed.media_consent_missing");
		assertThat(problem.get("students").get(0).get("displayName").asString()).isEqualTo("Abel");
		assertThat(code(post(f.api() + "/posts", f.admin(), Map.of("media", refs(photo)), 422))).isEqualTo("feed.media_confirmation_required");
		grant(f.guardian1(), f.s1());
		JsonNode school = post(f.api() + "/posts", f.admin(), body, 201);
		String media = school.get("media").get(0).get("mediaId").asString();
		// the whole school sees the post and the photo
		for (Persona viewer : List.of(f.guardian1(), f.guardian2(), f.otherGuardian(), f.otherStudent(), f.otherTeacher(), f.lead())) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(200);
		}
		for (Persona viewer : List.of(f.restricted(), f.revoked(), this.personas.newUser("gate-school-stranger"))) {
			assertThat(mediaUrlStatus(viewer, media)).as(viewer.name()).isEqualTo(404);
		}
		// but the tag is shown only to the child's family, their classmates and teachers, and managers
		String url = f.api() + "/posts/" + school.get("id").asString();
		for (Persona knows : List.of(f.guardian1(), f.student2(), f.lead(), f.admin())) {
			assertThat(get(url, knows).get("tags")).as(knows.name()).hasSize(1);
		}
		for (Persona stranger : List.of(f.guardian2(), f.otherGuardian(), f.otherStudent(), f.otherTeacher())) {
			assertThat(get(url, stranger).get("tags")).as(stranger.name()).isEmpty();
		}
		decide(f.guardian1(), f.s1(), "WITHDRAWN");
		assertThat(mediaUrlStatus(f.otherGuardian(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(f.admin(), media)).isEqualTo(200);
	}

	@Test
	void anApprovedContributionRunsTheGateToo() throws Exception {
		Feed f = feed("gate-contribution");
		setting(f.schoolId(), "feed.parent_contributions.enabled", true);
		UUID photo = photo(f.guardian1(), f.classId());
		JsonNode pending = post(f.classApi() + "/contributions", f.guardian1(),
				Map.of("body", "Abel at home", "media", refs(photo), "studentIds", List.of(f.s1().toString())), 201);
		assertThat(pending.get("status").asString()).isEqualTo("PENDING_APPROVAL");
		String approve = f.api() + "/contributions/" + pending.get("id").asString() + "/approve";
		JsonNode problem = post(approve, f.lead(), Map.of(), 422);
		assertThat(code(problem)).isEqualTo("feed.media_consent_missing");
		grant(f.guardian1(), f.s1());
		JsonNode approved = post(approve, f.lead(), Map.of(), 200);
		assertThat(approved.get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(approved.get("contribution").asBoolean()).isTrue();
	}

}
