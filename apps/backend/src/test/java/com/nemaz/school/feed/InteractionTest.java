package com.nemaz.school.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.DomainEventRecorder;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Comments, reactions, seen-by counts and reports. */
@IntegrationTest
class InteractionTest extends FeedTestSupport {

	@Autowired
	DomainEventRecorder recorded;

	private String postId(Feed f, Persona author) throws Exception {
		return classPost(author, f, "Discuss").get("id").asString();
	}

	private String comments(Feed f, String postId) {
		return f.api() + "/posts/" + postId + "/comments";
	}

	@Test
	void commentingFollowsTheSwitchesAndTheRelationToThePost() throws Exception {
		Feed f = feed("comment-rules");
		String id = postId(f, f.lead());
		// teachers and managers always may; a guardian with the right flags may by default; students may not by default
		for (Persona commenter : List.of(f.co(), f.admin(), f.guardian1(), f.guardian2())) {
			post(comments(f, id), commenter, Map.of("body", "Thanks from " + commenter.name()), 201);
		}
		assertThat(code(post(comments(f, id), f.student1(), Map.of("body", "Hi"), 403))).isEqualTo("feed.comment_not_allowed");
		setting(f.schoolId(), "feed.comments.students_can_comment", true);
		assertThat(post(comments(f, id), f.student1(), Map.of("body", "Hi"), 201).get("mine").asBoolean()).isTrue();
		// guardians need can_message_staff; other families, restricted and revoked links cannot find the post
		this.jdbc.update("update student_guardians set can_message_staff = false where guardian_id = ?", f.guardian2().userId());
		assertThat(code(post(comments(f, id), f.guardian2(), Map.of("body", "Hi"), 403))).isEqualTo("feed.comment_not_allowed");
		for (Persona outsider : List.of(f.otherGuardian(), f.otherStudent(), f.restricted(), f.revoked(), f.otherTeacher())) {
			call(HttpMethod.POST, comments(f, id), outsider, Map.of("body", "Hi"), 404);
			call(HttpMethod.GET, comments(f, id), outsider, null, 404);
		}
		setting(f.schoolId(), "feed.comments.guardians_can_comment", false);
		assertThat(code(post(comments(f, id), f.guardian1(), Map.of("body", "Hi"), 403))).isEqualTo("feed.comment_not_allowed");
		// the toggle per post, and the school default for new posts
		String quiet = classPost(f.lead(), f, "No comments please", 201, Map.of("commentsEnabled", false)).get("id").asString();
		assertThat(code(post(comments(f, quiet), f.co(), Map.of("body", "Hi"), 403))).isEqualTo("feed.comments_disabled");
		setting(f.schoolId(), "feed.comments.default_enabled", false);
		JsonNode byDefault = classPost(f.lead(), f, "Default off");
		assertThat(byDefault.get("commentsEnabled").asBoolean()).isFalse();
		assertThat(byDefault.get("rights").get("canComment").asBoolean()).isFalse();
		// empty comments are refused
		assertThat(code(post(comments(f, id), f.co(), Map.of("body", "   "), 400))).isIn("validation_failed", "feed.comment_empty");
		// a draft cannot be commented on
		String draft = classPost(f.lead(), f, "Draft", 201, Map.of("mode", "DRAFT")).get("id").asString();
		call(HttpMethod.POST, comments(f, draft), f.lead(), Map.of("body", "Hi"), 404);
	}

	@Test
	void commenterNamesFollowThePrivacyRulesAndNoPhoneNumbersAppear() throws Exception {
		Feed f = feed("comment-privacy");
		setting(f.schoolId(), "feed.comments.students_can_comment", true);
		String id = postId(f, f.lead());
		post(comments(f, id), f.guardian1(), Map.of("body", "From a parent"), 201);
		post(comments(f, id), f.student1(), Map.of("body", "From Abel"), 201);
		post(comments(f, id), f.co(), Map.of("body", "From the co-teacher"), 201);

		// staff see every name; a student sees the teacher and classmates but not parents; another family sees only teachers
		JsonNode asLead = get(comments(f, id), f.lead());
		assertThat(asLead.get("items").findValuesAsString("displayName")).containsExactly(f.guardian1().name(), "Abel", f.co().name());
		JsonNode asClassmate = get(comments(f, id), f.student2());
		assertThat(authorNames(asClassmate)).containsExactly(null, "Abel", f.co().name());
		JsonNode asOtherFamily = get(comments(f, id), f.guardian2());
		assertThat(authorNames(asOtherFamily)).containsExactly(null, null, f.co().name());
		assertThat(asOtherFamily.get("items").get(0).get("author").get("role").asString()).isEqualTo("GUARDIAN");
		// Abel's own guardian sees Abel; the commenting parent sees herself
		assertThat(authorNames(get(comments(f, id), f.guardian1()))).containsExactly(f.guardian1().name(), "Abel", f.co().name());
		// no phone number or email of anybody is ever in a comment payload
		for (Persona viewer : List.of(f.lead(), f.student2(), f.guardian2(), f.admin())) {
			String body = get(comments(f, id), viewer).toString();
			assertThat(body).as(viewer.name()).doesNotContain("+251").doesNotContain("phone").doesNotContain("@");
		}
		String post = get(f.api() + "/posts/" + id, f.student2()).toString();
		assertThat(post).doesNotContain("+251").doesNotContain("phone");
	}

	private static List<String> authorNames(JsonNode page) {
		List<String> names = new ArrayList<>();
		page.get("items").forEach((c) -> names.add(c.get("author").has("displayName") ? c.get("author").get("displayName").asString() : null));
		return names;
	}

	@Test
	void theLeadAndAdministratorsModerateCommentsAuthorsDeleteTheirOwn() throws Exception {
		Feed f = feed("comment-moderation");
		String id = postId(f, f.lead());
		String c1 = post(comments(f, id), f.guardian1(), Map.of("body", "Rude comment"), 201).get("id").asString();
		String c2 = post(comments(f, id), f.guardian2(), Map.of("body", "Nice comment"), 201).get("id").asString();
		String hide = comments(f, id) + "/" + c1 + "/hide";
		call(HttpMethod.POST, hide, f.co(), null, 403);
		call(HttpMethod.POST, hide, f.subject(), null, 403);
		call(HttpMethod.POST, hide, f.guardian2(), null, 403);
		call(HttpMethod.POST, hide, f.otherGuardian(), null, 404);
		assertThat(post(hide, f.lead(), null, 200).get("status").asString()).isEqualTo("HIDDEN");
		// hidden from families and colleagues, still listed to its author and to moderators
		assertThat(get(comments(f, id), f.guardian2()).get("items").findValuesAsString("id")).containsExactly(c2);
		assertThat(get(comments(f, id), f.co()).get("items").findValuesAsString("id")).containsExactly(c2);
		assertThat(get(comments(f, id), f.guardian1()).get("items").findValuesAsString("id")).containsExactly(c1, c2);
		assertThat(get(comments(f, id), f.admin()).get("items").findValuesAsString("id")).containsExactly(c1, c2);
		assertThat(get(f.api() + "/posts/" + id, f.guardian2()).get("commentCount").asInt()).isEqualTo(1);
		assertThat(post(comments(f, id) + "/" + c1 + "/unhide", f.admin(), null, 200).get("status").asString()).isEqualTo("VISIBLE");
		// deleting: the author or a moderator, nobody else
		call(HttpMethod.DELETE, comments(f, id) + "/" + c2, f.guardian1(), null, 403);
		call(HttpMethod.DELETE, comments(f, id) + "/" + c2, f.co(), null, 403);
		call(HttpMethod.DELETE, comments(f, id) + "/" + c2, f.guardian2(), null, 204);
		call(HttpMethod.DELETE, comments(f, id) + "/" + c1, f.lead(), null, 204);
		call(HttpMethod.DELETE, comments(f, id) + "/" + c1, f.lead(), null, 404);
		assertThat(get(comments(f, id), f.lead()).get("items")).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action in ('feed.comment.hide', 'feed.comment.unhide', 'feed.comment.delete') and school_id = ?",
				Integer.class, f.schoolId())).isEqualTo(4);
	}

	@Test
	void commentsAreIdempotentWithAKeyAndPaged() throws Exception {
		Feed f = feed("comment-paging");
		String id = postId(f, f.lead());
		String url = comments(f, id);
		String body = this.json.writeValueAsString(Map.of("body", "Once"));
		var first = this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(f.guardian1().bearer()).header("Idempotency-Key", "k-1")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
		var second = this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(f.guardian1().bearer()).header("Idempotency-Key", "k-1")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "true")).andReturn();
		assertThat(this.json.readTree(second.getResponse().getContentAsString()))
			.isEqualTo(this.json.readTree(first.getResponse().getContentAsString()));
		for (int i = 0; i < 4; i++) {
			post(url, f.co(), Map.of("body", "Comment " + i), 201);
		}
		JsonNode page = get(url + "?limit=2", f.lead());
		assertThat(page.get("items")).hasSize(2);
		List<String> all = new ArrayList<>(page.get("items").findValuesAsString("id"));
		String cursor = page.get("nextCursor").asString();
		while (cursor != null) {
			JsonNode next = get(url + "?limit=2&cursor=" + cursor, f.lead());
			all.addAll(next.get("items").findValuesAsString("id"));
			cursor = next.has("nextCursor") ? next.get("nextCursor").asString() : null;
		}
		assertThat(all).hasSize(5).doesNotHaveDuplicates();
		assertThat(get(f.api() + "/posts/" + id, f.lead()).get("commentCount").asInt()).isEqualTo(5);
	}

	@Test
	void everyUserHasOneReactionEvenUnderConcurrentRequests() throws Exception {
		Feed f = feed("reactions");
		String id = postId(f, f.lead());
		String url = f.api() + "/posts/" + id + "/reaction";
		call(HttpMethod.PUT, url, f.guardian1(), Map.of("reaction", "LIKE"), 200);
		JsonNode changed = call(HttpMethod.PUT, url, f.guardian1(), Map.of("reaction", "HEART"), 200);
		assertThat(changed.get("counts").get("HEART").asInt()).isEqualTo(1);
		assertThat(changed.get("counts").has("LIKE")).isFalse();
		assertThat(changed.get("mine").asString()).isEqualTo("HEART");
		// a flood of simultaneous, different reactions by one user still leaves exactly one
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			String[] kinds = { "LIKE", "HEART", "CLAP", "STAR" };
			for (int i = 0; i < 16; i++) {
				String kind = kinds[i % kinds.length];
				results.add(pool.submit(() -> statusOf(HttpMethod.PUT, url, f.student2(), Map.of("reaction", kind))));
			}
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.queryForObject("select count(*) from post_reactions where post_id = ? and user_id = ?", Integer.class,
				UUID.fromString(id), f.student2().userId())).isEqualTo(1);
		// the post carries the totals and the caller's own reaction
		JsonNode seen = get(f.api() + "/posts/" + id, f.guardian2());
		int total = 0;
		for (var count : seen.get("reactionCounts").properties()) {
			total += count.getValue().asInt();
		}
		assertThat(total).isEqualTo(2);
		assertThat(seen.has("myReaction")).isFalse();
		// removing is idempotent; people who cannot see the post cannot react
		call(HttpMethod.DELETE, url, f.guardian1(), null, 200);
		assertThat(call(HttpMethod.DELETE, url, f.guardian1(), null, 200).get("mine")).isNull();
		call(HttpMethod.PUT, url, f.otherGuardian(), Map.of("reaction", "LIKE"), 404);
		call(HttpMethod.PUT, url, f.restricted(), Map.of("reaction", "LIKE"), 404);
		call(HttpMethod.PUT, url, f.guardian1(), Map.of("reaction", "ANGRY"), 400);
	}

	@Test
	void seenByIsACountForTheAuthorAndStaffAndNeverNames() throws Exception {
		Feed f = feed("seen-by");
		String id = postId(f, f.co());
		String view = f.api() + "/posts/" + id + "/view";
		call(HttpMethod.POST, view, f.guardian1(), null, 204);
		call(HttpMethod.POST, view, f.guardian1(), null, 204);
		call(HttpMethod.POST, view, f.guardian2(), null, 204);
		call(HttpMethod.POST, view, f.student2(), null, 204);
		call(HttpMethod.POST, view, f.co(), null, 204);
		call(HttpMethod.POST, view, f.otherGuardian(), null, 404);
		for (Persona staff : List.of(f.co(), f.lead(), f.subject(), f.admin())) {
			JsonNode post = get(f.api() + "/posts/" + id, staff);
			assertThat(post.get("seenCount").asInt()).as(staff.name()).isEqualTo(3);
			assertThat(post.toString()).doesNotContain(f.guardian1().name()).doesNotContain(f.guardian2().name());
		}
		for (Persona family : List.of(f.guardian1(), f.student1())) {
			assertThat(get(f.api() + "/posts/" + id, family).has("seenCount")).as(family.name()).isFalse();
		}
	}

	@Test
	void reportingIsAStubForTheModerationMilestone() throws Exception {
		Feed f = feed("report");
		String id = postId(f, f.lead());
		String url = f.api() + "/posts/" + id + "/report";
		call(HttpMethod.POST, url, f.guardian1(), Map.of("reason", "PRIVACY"), 202);
		call(HttpMethod.POST, url, f.guardian1(), Map.of("reason", "NOPE"), 400);
		call(HttpMethod.POST, url, f.otherGuardian(), Map.of("reason", "SPAM"), 404);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'feed.post.report' and target_id = ?",
				Integer.class, UUID.fromString(id))).isEqualTo(1);
		assertThat(this.recorded.of(ContentReported.class)).anySatisfy((event) -> {
			assertThat(event.contentId()).isEqualTo(UUID.fromString(id));
			assertThat(event.reporterId()).isEqualTo(f.guardian1().userId());
			assertThat(event.reasonCode()).isEqualTo("PRIVACY");
		});
	}

}
