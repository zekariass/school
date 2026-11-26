package com.nemaz.school.feed;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Posting rights, who sees what, pinning, moderation, pagination and the family home feed. */
@IntegrationTest
class PostFeedTest extends FeedTestSupport {

	@Autowired
	CacheManager caches;

	private JsonNode publish(Persona who, Feed f, String text) throws Exception {
		return classPost(who, f, text);
	}

	@Test
	void onlyTheClassTeachersAndManagersPostToAClass() throws Exception {
		Feed f = feed("post-rights");
		for (Persona poster : List.of(f.lead(), f.co(), f.subject(), f.assistant(), f.admin())) {
			JsonNode post = publish(poster, f, "Hello from " + poster.name());
			assertThat(post.get("status").asString()).isEqualTo("PUBLISHED");
			assertThat(post.get("author").get("role").asString())
				.isEqualTo((poster == f.admin()) ? "SCHOOL_ADMIN" : "TEACHER");
			assertThat(post.get("author").get("userId").asString()).isEqualTo(poster.userId().toString());
		}
		// students and guardians see the class but may not post (403); the others cannot even find it (404)
		for (Persona viewer : List.of(f.student1(), f.guardian1())) {
			assertThat(code(classPost(viewer, f, "No", 403, Map.of()))).isEqualTo("feed.access_denied");
		}
		for (Persona outsider : List.of(f.otherTeacher(), f.otherGuardian(), f.otherStudent(), f.restricted(), f.revoked())) {
			assertThat(code(classPost(outsider, f, "No", 404, Map.of()))).isIn("class.not_found", "school.not_found");
		}
		Persona stranger = this.personas.newUser("stranger");
		assertThat(code(classPost(stranger, f, "No", 404, Map.of()))).isEqualTo("school.not_found");
		Persona otherAdmin = this.personas.schoolAdmin(this.personas.newSchool("elsewhere"), "elsewhere-admin");
		assertThat(code(classPost(otherAdmin, f, "No", 404, Map.of()))).isEqualTo("school.not_found");

		// the setting turns assistants off
		setting(f.schoolId(), "feed.assistants_can_post", false);
		assertThat(code(classPost(f.assistant(), f, "No", 403, Map.of()))).isEqualTo("feed.access_denied");
		assertThat(classPost(f.co(), f, "Still fine").get("status").asString()).isEqualTo("PUBLISHED");
	}

	@Test
	void anEmptyPostAndBadTagsAreRefused() throws Exception {
		Feed f = feed("post-validation");
		assertThat(code(classPost(f.lead(), f, null, 400, Map.of()))).isEqualTo("feed.post_empty");
		assertThat(code(classPost(f.lead(), f, "  ", 400, Map.of()))).isEqualTo("feed.post_empty");
		// a student of another class is not a valid tag
		assertThat(code(classPost(f.lead(), f, "x", 400, Map.of("studentIds", List.of(f.o1().toString())))))
			.isEqualTo("feed.tag_invalid");
		assertThat(code(classPost(f.lead(), f, "x", 400, Map.of("bodyLanguageCode", "zz")))).isEqualTo("feed.translation_language_invalid");
	}

	@Test
	void schoolWidePostsNeedTheFeedPermission() throws Exception {
		Feed f = feed("post-school");
		JsonNode post = post(f.api() + "/posts", f.admin(), Map.of("body", "Holiday on Monday"), 201);
		assertThat(post.get("scope").asString()).isEqualTo("SCHOOL");
		assertThat(post.get("classId")).isNull();
		assertThat(code(post(f.api() + "/posts", f.lead(), Map.of("body", "No"), 403))).isEqualTo("feed.access_denied");
		assertThat(code(post(f.api() + "/posts", f.guardian1(), Map.of("body", "No"), 403))).isEqualTo("feed.access_denied");
		assertThat(code(post(f.api() + "/posts", this.personas.newUser("nobody"), Map.of("body", "No"), 404)))
			.isEqualTo("school.not_found");

		// the school feed: members, staff and guardians of the school; not the restricted or revoked guardian
		String id = post.get("id").asString();
		for (Persona viewer : List.of(f.admin(), f.lead(), f.otherTeacher(), f.student1(), f.otherStudent(), f.guardian1(),
				f.otherGuardian())) {
			assertThat(ids(get(f.api() + "/feed", viewer))).as(viewer.name()).contains(id);
		}
		for (Persona viewer : List.of(f.restricted(), f.revoked())) {
			assertThat(code(call(HttpMethod.GET, f.api() + "/feed", viewer, null, 404))).isEqualTo("school.not_found");
		}
		assertThat(code(call(HttpMethod.GET, f.api() + "/feed", this.personas.newUser("nobody2"), null, 404)))
			.isEqualTo("school.not_found");
		// school posts are not in a class feed and class posts are not in the school feed
		publish(f.lead(), f, "Class only");
		assertThat(get(f.api() + "/feed", f.guardian1()).get("items")).hasSize(1);
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).doesNotContain(id);
	}

	@Test
	void everyReadEndpointKeepsOutPeopleWhoMayNotSeeTheClass() throws Exception {
		Feed f = feed("post-privacy");
		JsonNode post = publish(f.lead(), f, "Secret classroom news");
		String id = post.get("id").asString();
		for (Persona viewer : List.of(f.lead(), f.co(), f.subject(), f.assistant(), f.admin(), f.student1(), f.student2(),
				f.guardian1(), f.guardian2())) {
			assertThat(ids(get(f.classApi() + "/feed", viewer))).as("feed " + viewer.name()).containsExactly(id);
			assertThat(get(f.api() + "/posts/" + id, viewer).get("body").asString()).as("post " + viewer.name())
				.isEqualTo("Secret classroom news");
		}
		for (Persona viewer : List.of(f.otherTeacher(), f.otherStudent(), f.otherGuardian(), f.restricted(), f.revoked())) {
			assertThat(call(HttpMethod.GET, f.classApi() + "/feed", viewer, null, 404).get("code").asString())
				.as("feed " + viewer.name())
				.isIn("class.not_found", "school.not_found");
			assertThat(call(HttpMethod.GET, f.api() + "/posts/" + id, viewer, null, 404).get("code").asString())
				.as("post " + viewer.name())
				.isIn("feed.post_not_found", "school.not_found");
		}
		// a guardian whose link lacks can_view_academics sees nothing of the class
		this.jdbc.update("update student_guardians set can_view_academics = false where guardian_id = ?", f.guardian2().userId());
		call(HttpMethod.GET, f.classApi() + "/feed", f.guardian2(), null, 404);
		call(HttpMethod.GET, f.api() + "/posts/" + id, f.guardian2(), null, 404);
		call(HttpMethod.GET, f.api() + "/feed", f.guardian2(), null, 404);
		// former class members have no access
		Persona former = this.personas.personaFor("former", this.personas.newStudent(f.schoolId(), "Former"));
		this.jdbc.update("""
				insert into class_students (id, class_id, school_id, student_id, status, left_at, left_reason)
				values (?, ?, ?, ?, 'FORMER', now(), 'TRANSFERRED_CLASS')""", newId(), f.classId(), f.schoolId(), former.userId());
		call(HttpMethod.GET, f.classApi() + "/feed", former, null, 404);
		call(HttpMethod.GET, f.api() + "/posts/" + id, former, null, 404);
		// the school of another administrator
		Persona otherAdmin = this.personas.schoolAdmin(this.personas.newSchool("elsewhere2"), "elsewhere2-admin");
		call(HttpMethod.GET, f.classApi() + "/feed", otherAdmin, null, 404);
		call(HttpMethod.GET, f.api() + "/posts/" + id, otherAdmin, null, 404);
		// a post id of another school is not found through this school
		JsonNode foreign = publishIn("post-privacy-2");
		call(HttpMethod.GET, f.api() + "/posts/" + foreign.get("id").asString(), f.lead(), null, 404);
	}

	private JsonNode publishIn(String label) throws Exception {
		Feed other = feed(label);
		return publish(other.lead(), other, "Other school");
	}

	@Test
	void draftsAndUnpublishedPostsAreVisibleToTheAuthorAndModeratorsOnly() throws Exception {
		Feed f = feed("post-drafts");
		JsonNode draft = classPost(f.co(), f, "My draft", 201, Map.of("mode", "DRAFT"));
		String url = f.api() + "/posts/" + draft.get("id").asString();
		assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
		for (Persona viewer : List.of(f.co(), f.lead(), f.admin())) {
			assertThat(get(url, viewer).get("status").asString()).as(viewer.name()).isEqualTo("DRAFT");
		}
		for (Persona viewer : List.of(f.subject(), f.assistant(), f.student1(), f.guardian1(), f.otherTeacher())) {
			call(HttpMethod.GET, url, viewer, null, 404);
		}
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).isEmpty();
		assertThat(ids(get(f.classApi() + "/feed", f.lead()))).isEmpty();
		// the author finds it among their own posts
		assertThat(ids(get(f.api() + "/my-posts?status=DRAFT", f.co()))).containsExactly(draft.get("id").asString());
		assertThat(ids(get(f.api() + "/my-posts", f.lead()))).isEmpty();
		// only the author publishes it (the lead, as moderator, may too)
		assertThat(code(post(url + "/publish", f.subject(), null, 404))).isEqualTo("feed.post_not_found");
		assertThat(post(url + "/publish", f.co(), null, 200).get("status").asString()).isEqualTo("PUBLISHED");
	}

	@Test
	void authorsEditAndDeleteTheirOwnPostsAndTheLeadModeratesTheRest() throws Exception {
		Feed f = feed("post-edit");
		JsonNode post = publish(f.co(), f, "Original");
		String url = f.api() + "/posts/" + post.get("id").asString();
		assertThat(post.get("editedAt")).isNull();
		JsonNode edited = call(HttpMethod.PATCH, url, f.co(), Map.of("body", "Edited"), 200);
		assertThat(edited.get("body").asString()).isEqualTo("Edited");
		assertThat(edited.get("editedAt").isNull()).isFalse();
		// nobody else edits it, not even the lead or the administrator
		for (Persona other : List.of(f.lead(), f.admin(), f.subject())) {
			assertThat(code(call(HttpMethod.PATCH, url, other, Map.of("body", "Hijacked"), 403))).isEqualTo("feed.access_denied");
		}
		call(HttpMethod.PATCH, url, f.guardian1(), Map.of("body", "Hijacked"), 403);
		call(HttpMethod.PATCH, url, f.otherGuardian(), Map.of("body", "Hijacked"), 404);
		// a subject teacher cannot delete a colleague's post; the lead (moderator) can, and it is audited as moderation
		assertThat(code(call(HttpMethod.DELETE, url, f.subject(), null, 403))).isEqualTo("feed.access_denied");
		call(HttpMethod.DELETE, url, f.guardian1(), null, 403);
		call(HttpMethod.DELETE, url, f.lead(), null, 204);
		assertThat(this.jdbc.queryForObject("select metadata ->> 'moderation' from audit_logs where action = 'feed.post.delete' and target_id = ?",
				String.class, UUID.fromString(post.get("id").asString()))).isEqualTo("true");
		for (Persona viewer : List.of(f.co(), f.lead(), f.admin(), f.guardian1())) {
			call(HttpMethod.GET, url, viewer, null, 404);
		}
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).isEmpty();

		// authors delete their own post (and its photo goes with it)
		UUID photo = photo(f.co(), f.classId());
		JsonNode withPhoto = classPost(f.co(), f, "With photo", 201, Map.of("media", refs(photo), "confirmNoUnconsentedStudents", true));
		call(HttpMethod.DELETE, f.api() + "/posts/" + withPhoto.get("id").asString(), f.co(), null, 204);
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, photo)).isEqualTo("DELETED");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.post.delete' and metadata ->> 'moderation' = 'false' and school_id = ?",
				Integer.class, f.schoolId())).isEqualTo(1);
	}

	@Test
	void pinnedPostsComeFirstAndThePinLimitHolds() throws Exception {
		Feed f = feed("post-pins");
		List<String> posts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			posts.add(publish(f.lead(), f, "Post " + i).get("id").asString());
		}
		for (int i = 0; i < 3; i++) {
			assertThat(call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(i) + "/pin", f.lead(), null, 200).get("pinned").asBoolean()).isTrue();
		}
		JsonNode problem = call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(3) + "/pin", f.lead(), null, 409);
		assertThat(code(problem)).isEqualTo("feed.pin_limit_reached");
		// pinned first (newest of the pinned first), then the rest newest first
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).containsExactly(posts.get(2), posts.get(1), posts.get(0),
				posts.get(4), posts.get(3));
		// pinning is idempotent and an unpin frees a slot
		call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(0) + "/pin", f.lead(), null, 200);
		call(HttpMethod.DELETE, f.api() + "/posts/" + posts.get(0) + "/pin", f.lead(), null, 200);
		call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(3) + "/pin", f.lead(), null, 200);
		// families cannot pin; another teacher of the class cannot pin a colleague's post, but the lead and admin can
		call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(4) + "/pin", f.guardian1(), null, 403);
		call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(4) + "/pin", f.co(), null, 403);
		setting(f.schoolId(), "feed.pin_limit", 0);
		assertThat(code(call(HttpMethod.PUT, f.api() + "/posts/" + posts.get(4) + "/pin", f.admin(), null, 409)))
			.isEqualTo("feed.pin_limit_reached");
		// an unpublished post cannot be pinned
		JsonNode draft = classPost(f.lead(), f, "Draft", 201, Map.of("mode", "DRAFT"));
		setting(f.schoolId(), "feed.pin_limit", 5);
		assertThat(code(call(HttpMethod.PUT, f.api() + "/posts/" + draft.get("id").asString() + "/pin", f.lead(), null, 409)))
			.isEqualTo("feed.pin_not_published");
	}

	@Test
	void moderatorsHideAndAuthorsArchive() throws Exception {
		Feed f = feed("post-hide");
		String id = publish(f.co(), f, "Questionable").get("id").asString();
		String url = f.api() + "/posts/" + id;
		call(HttpMethod.POST, url + "/hide", f.subject(), Map.of(), 403);
		call(HttpMethod.POST, url + "/hide", f.co(), Map.of(), 403);
		JsonNode hidden = post(url + "/hide", f.lead(), Map.of("reason", "Please rephrase"), 200);
		assertThat(hidden.get("status").asString()).isEqualTo("HIDDEN");
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).isEmpty();
		call(HttpMethod.GET, url, f.guardian1(), null, 404);
		JsonNode seenByAuthor = get(url, f.co());
		assertThat(seenByAuthor.get("rejectionReason").asString()).isEqualTo("Please rephrase");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.post.hide' and target_id = ?",
				Integer.class, UUID.fromString(id))).isEqualTo(1);
		assertThat(post(url + "/unhide", f.lead(), null, 200).get("status").asString()).isEqualTo("PUBLISHED");
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).containsExactly(id);
		// the author takes it down and brings it back
		assertThat(post(url + "/archive", f.co(), null, 200).get("status").asString()).isEqualTo("ARCHIVED");
		assertThat(ids(get(f.classApi() + "/feed", f.guardian1()))).isEmpty();
		assertThat(post(url + "/publish", f.co(), null, 200).get("status").asString()).isEqualTo("PUBLISHED");
	}

	@Test
	void keysetPagesNeverRepeatOrSkipEvenWhenPostsArrive() throws Exception {
		Feed f = feed("post-paging");
		List<String> all = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			all.add(0, publish(f.lead(), f, "Post " + i).get("id").asString());
		}
		JsonNode first = get(f.classApi() + "/feed?limit=2", f.guardian1());
		assertThat(ids(first)).containsExactly(all.get(0), all.get(1));
		assertThat(first.get("nextCursor").asString()).isNotBlank();
		// a newer post and a deleted older one do not disturb the pages already handed out
		String newest = publish(f.lead(), f, "Late arrival").get("id").asString();
		List<String> seen = new ArrayList<>(ids(first));
		String cursor = first.get("nextCursor").asString();
		while (cursor != null) {
			JsonNode page = get(f.classApi() + "/feed?limit=2&cursor=" + cursor, f.guardian1());
			seen.addAll(ids(page));
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
		}
		assertThat(seen).containsExactlyElementsOf(all).doesNotContain(newest);
		assertThat(new LinkedHashSet<>(seen)).hasSameSizeAs(seen);
		assertThat(code(call(HttpMethod.GET, f.classApi() + "/feed?cursor=not-a-cursor", f.guardian1(), null, 400)))
			.isEqualTo("invalid_cursor");
		assertThat(get(f.classApi() + "/feed?limit=1000", f.guardian1()).get("items")).hasSize(6);
	}

	@Test
	void theFamilyHomeFeedSpansSchoolsAndRespectsLinksAndSettings() throws Exception {
		Feed a = feed("family-a2");
		Feed b = feed("family-b2");
		Persona parent = this.personas.newUser("two-schools-parent2");
		this.personas.linkGuardian(a.s1(), parent.userId(), "ACTIVE", false);
		this.personas.linkGuardian(b.s2(), parent.userId(), "ACTIVE", false);
		String inA = publish(a.lead(), a, "News of A").get("id").asString();
		String inB = publish(b.lead(), b, "News of B").get("id").asString();
		String schoolWideB = post(b.api() + "/posts", b.admin(), Map.of("body", "School B news"), 201).get("id").asString();
		// a post of another class of school A (their child is not in it) must not appear
		post(a.api() + "/classes/" + a.otherClassId() + "/posts", a.otherTeacher(), Map.of("body", "News of 2B"), 201);

		JsonNode home = get("/api/v1/me/feed", parent);
		assertThat(ids(home)).containsExactly(schoolWideB, inB, inA);
		// filtered by child
		assertThat(ids(get("/api/v1/me/feed?studentId=" + a.s1(), parent))).containsExactly(inA);
		assertThat(ids(get("/api/v1/me/feed?studentId=" + b.s2(), parent))).containsExactly(schoolWideB, inB);
		// somebody else's child, or an unknown one, is not found
		call(HttpMethod.GET, "/api/v1/me/feed?studentId=" + a.s2(), parent, null, 404);
		// a link that is restricted or revoked contributes nothing
		this.jdbc.update("update student_guardians set custody_restricted = true where guardian_id = ? and student_id = ?",
				parent.userId(), b.s2());
		assertThat(ids(get("/api/v1/me/feed", parent))).containsExactly(inA);
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ? and student_id = ?",
				parent.userId(), a.s1());
		assertThat(ids(get("/api/v1/me/feed", parent))).isEmpty();
		// a guardian without the academics flag sees nothing of that child
		this.jdbc.update("update student_guardians set status = 'ACTIVE', revoked_at = null, can_view_academics = false where guardian_id = ? and student_id = ?",
				parent.userId(), a.s1());
		assertThat(ids(get("/api/v1/me/feed", parent))).isEmpty();
		// an adult without children and a teacher get an empty feed
		assertThat(ids(get("/api/v1/me/feed", this.personas.newUser("childless")))).isEmpty();
		assertThat(ids(get("/api/v1/me/feed", a.lead()))).isEmpty();
		// a student reads their own class and school
		assertThat(ids(get("/api/v1/me/feed", a.student1()))).containsExactly(inA);
		// a school that switched the feed off disappears from the home feed
		this.jdbc.update("update student_guardians set custody_restricted = false where guardian_id = ? and student_id = ?",
				parent.userId(), b.s2());
		this.jdbc.update("insert into feature_overrides (feature_key, scope_type, school_id, enabled) values ('feed', 'SCHOOL', ?, false)",
				b.schoolId());
		try {
			this.caches.getCache("school-configuration").clear();
			assertThat(ids(get("/api/v1/me/feed", parent))).isEmpty();
			assertThat(code(call(HttpMethod.GET, b.classApi() + "/feed", b.guardian2(), null, 403))).isEqualTo("feed.feature_disabled");
		}
		finally {
			this.jdbc.update("delete from feature_overrides where school_id = ?", b.schoolId());
			this.caches.getCache("school-configuration").clear();
		}
	}

	@Test
	void classMembersAreNotifiedOnlyWhenTheSettingIsOn() throws Exception {
		Feed f = feed("post-notify");
		publish(f.lead(), f, "Quiet");
		assertThat(notificationCount(f.guardian1().userId(), "NEW_POST")).isZero();
		setting(f.schoolId(), "feed.notify_class_members", true);
		String id = publish(f.lead(), f, "Loud").get("id").asString();
		awaitNotifications(f.guardian1().userId(), "NEW_POST", 1);
		awaitNotifications(f.guardian2().userId(), "NEW_POST", 1);
		awaitNotifications(f.student1().userId(), "NEW_POST", 1);
		assertThat(notificationCount(f.otherGuardian().userId(), "NEW_POST")).isZero();
		assertThat(notificationCount(f.restricted().userId(), "NEW_POST")).isZero();
		assertThat(notificationCount(f.lead().userId(), "NEW_POST")).isZero();
		Map<String, Object> row = this.jdbc.queryForMap("select title, body, data::text as data from notifications where user_id = ? and type_code = 'NEW_POST'",
				f.guardian2().userId());
		assertThat(row.get("body").toString()).doesNotContain("Loud");
		assertThat(row.get("data").toString()).contains("/feed/posts/" + id);
	}

}
