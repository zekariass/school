package com.nemaz.school.portfolio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioWorkflow;
import com.nemaz.school.portfolio.application.PortfolioWorkflow.Move;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rule book P2: the status workflow, who reviews, deletion, archiving, featuring, the queue and the history. */
@IntegrationTest
class PortfolioWorkflowTest extends PortfolioTestSupport {

	/** An action of the API with who performs it. */
	private record Act(Move move, Function<Pf, Persona> actor, String path, Object body) {
	}

	private static final List<Act> ACTS = List.of(
			new Act(Move.SUBMIT, Pf::student1, "/submit", null),
			new Act(Move.APPROVE, Pf::lead, "/review", body("decision", "APPROVE")),
			new Act(Move.REQUEST_CHANGES, Pf::lead, "/review", body("decision", "REQUEST_CHANGES", "note", "please add more")),
			new Act(Move.REJECT, Pf::lead, "/review", body("decision", "REJECT", "note", "not appropriate")),
			new Act(Move.ARCHIVE, Pf::student1, "/archive", body()),
			new Act(Move.UNARCHIVE, Pf::lead, "/unarchive", body()));

	@Test
	void everyLegalTransitionWorks() throws Exception {
		Pf p = pf("wf1");
		int legal = 0;
		for (PortfolioStatus from : PortfolioStatus.values()) {
			for (Act act : ACTS) {
				PortfolioStatus to = PortfolioWorkflow.next(from, act.move()).orElse(null);
				if (to == null) {
					continue;
				}
				UUID item = insertItem(p, p.s1(), from.name(), "GUARDIANS", p.classId(), null);
				JsonNode result = post(p.item(item) + act.path(), act.actor().apply(p), act.body(), 200);
				assertThat(result.get("status").asString()).as(from + " " + act.move()).isEqualTo(to.name());
				assertThat(status(item)).isEqualTo(to.name());
				legal++;
			}
		}
		assertThat(legal).isEqualTo(7);
	}

	@Test
	void everyIllegalTransitionIsRefused() throws Exception {
		Pf p = pf("wf2");
		int illegal = 0;
		for (PortfolioStatus from : PortfolioStatus.values()) {
			for (Act act : ACTS) {
				if (PortfolioWorkflow.next(from, act.move()).isPresent()) {
					continue;
				}
				UUID item = insertItem(p, p.s1(), from.name(), "GUARDIANS", p.classId(), null);
				Persona actor = act.actor().apply(p);
				int expected = 409;
				if (from == PortfolioStatus.DRAFT && actor == p.lead()) {
					expected = 404; // a draft is invisible to everybody but its creator
				}
				int status = statusOf(HttpMethod.POST, p.item(item) + act.path(), actor, act.body());
				assertThat(status).as(from + " " + act.move()).isEqualTo(expected);
				assertThat(status(item)).isEqualTo(from.name());
				illegal++;
			}
		}
		assertThat(illegal).isEqualTo(6 * 6 - 7);
	}

	@Test
	void draftIsVisibleToItsCreatorOnly() throws Exception {
		Pf p = pf("wf3");
		UUID id = draft(p, p.student1(), "private draft");
		assertThat(get(p.item(id), p.student1()).get("status").asString()).isEqualTo("DRAFT");
		for (Persona other : List.of(p.lead(), p.co(), p.admin(), p.guardian1(), p.student2(), p.otherTeacher(), p.adminB())) {
			assertThat(statusOf(HttpMethod.GET, p.item(id), other, null)).as(other.name()).isEqualTo(404);
		}
		cap(p, "SCHOOL");
		UUID wide = id(create(p.student1(), p, textBody("wide draft", "x", "visibility", "SCHOOL"), 201));
		assertThat(statusOf(HttpMethod.GET, p.item(wide), p.student2(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, p.item(wide), p.guardian1(), null)).isEqualTo(404);
	}

	@Test
	void studentEditsOnlyDraftAndChangesRequested() throws Exception {
		Pf p = pf("wf4");
		UUID id = draft(p, p.student1(), "first");
		JsonNode edited = call(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "second", "reflection", "learned"), 200);
		assertThat(edited.get("title").asString()).isEqualTo("second");
		assertThat(edited.get("reflection").asString()).isEqualTo("learned");
		post(p.item(id) + "/submit", p.student1(), null, 200);
		assertThat(code(call(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "third"), 409))).isEqualTo("portfolio.invalid_state");
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "more"), 200);
		assertThat(call(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "third"), 200).get("title").asString()).isEqualTo("third");
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(code(call(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "fourth"), 409))).isEqualTo("portfolio.invalid_state");
		// only the student who wrote it edits it: a teacher who sees it, a classmate and a guardian are refused
		UUID mine = draft(p, p.student1(), "mine");
		assertThat(statusOf(HttpMethod.PATCH, p.item(mine), p.lead(), body("title", "x"))).isEqualTo(404);
		assertThat(statusOf(HttpMethod.PATCH, p.item(id), p.lead(), body("title", "x"))).isEqualTo(403);
		assertThat(statusOf(HttpMethod.PATCH, p.item(id), p.guardian1(), body("title", "x"))).isEqualTo(403);
	}

	@Test
	void resubmissionAppendsANewSubmittedRow() throws Exception {
		Pf p = pf("wf5");
		UUID id = submitted(p, p.student1(), "again");
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "add a picture"), 200);
		post(p.item(id) + "/submit", p.student1(), null, 200);
		List<Map<String, Object>> rows = this.jdbc.queryForList(
				"select action, from_status, to_status from portfolio_approvals where item_id = ? order by id", id);
		assertThat(rows).extracting((r) -> r.get("action") + ":" + r.get("from_status") + ">" + r.get("to_status"))
			.containsExactly("SUBMITTED:null>SUBMITTED", "CHANGES_REQUESTED:SUBMITTED>CHANGES_REQUESTED",
					"SUBMITTED:CHANGES_REQUESTED>SUBMITTED");
	}

	@Test
	void changesRequestedAndRejectedNeedANote() throws Exception {
		Pf p = pf("wf6");
		for (String decision : List.of("REQUEST_CHANGES", "REJECT")) {
			UUID id = submitted(p, p.student1(), "needs " + decision);
			assertThat(code(post(p.item(id) + "/review", p.lead(), body("decision", decision), 400))).isEqualTo("portfolio.note_required");
			assertThat(code(post(p.item(id) + "/review", p.lead(), body("decision", decision, "note", "   "), 400))).isEqualTo("portfolio.note_required");
			assertThat(code(post(p.item(id) + "/review", p.lead(), body("decision", decision, "note", "n".repeat(501)), 400)))
				.isEqualTo("portfolio.note_too_long");
			assertThat(status(id)).isEqualTo("SUBMITTED");
		}
		UUID id = submitted(p, p.student1(), "approve without note");
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
	}

	@Test
	void rejectedIsTheEnd() throws Exception {
		Pf p = pf("wf7");
		UUID id = submitted(p, p.student1(), "no");
		post(p.item(id) + "/review", p.lead(), body("decision", "REJECT", "note", "not suitable"), 200);
		assertThat(code(post(p.item(id) + "/submit", p.student1(), null, 409))).isEqualTo("portfolio.invalid_state");
		assertThat(statusOf(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "x"))).isEqualTo(409);
		assertThat(code(call(HttpMethod.DELETE, p.item(id), p.student1(), null, 403))).isEqualTo("portfolio.delete_not_allowed");
		// the student still sees it and the note; guardians never see a rejected item
		JsonNode view = get(p.item(id), p.student1());
		assertThat(view.get("status").asString()).isEqualTo("REJECTED");
		assertThat(view.get("reviewNote").asString()).isEqualTo("not suitable");
		assertThat(statusOf(HttpMethod.GET, p.item(id), p.guardian1(), null)).isEqualTo(404);
	}

	@Test
	void reviewerMayLowerVisibilityNeverRaise() throws Exception {
		Pf p = pf("wf8");
		cap(p, "SCHOOL");
		UUID id = id(create(p.student1(), p, textBody("wide", "x", "mode", "SUBMIT", "visibility", "CLASS"), 201));
		assertThat(code(post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "visibility", "SCHOOL"), 400)))
			.isEqualTo("portfolio.visibility_raise_not_allowed");
		JsonNode lowered = post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "visibility", "GUARDIANS"), 200);
		assertThat(lowered.get("visibility").asString()).isEqualTo("GUARDIANS");
		assertThat(statusOf(HttpMethod.GET, p.item(id), p.student2(), null)).isEqualTo(404);
		assertThat(statusOf(HttpMethod.GET, p.item(id), p.guardian1(), null)).isEqualTo(200);
	}

	@Test
	void everyTransitionAppendsOneHistoryRow() throws Exception {
		Pf p = pf("wf9");
		UUID id = id(create(p.student1(), p, textBody("full", "x"), 201));
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "more"), 200);
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "note", "good"), 200);
		call(HttpMethod.PUT, p.item(id) + "/featured", p.lead(), null, 200);
		call(HttpMethod.DELETE, p.item(id) + "/featured", p.lead(), null, 200);
		post(p.item(id) + "/archive", p.student1(), body(), 200);
		post(p.item(id) + "/unarchive", p.lead(), body(), 200);
		call(HttpMethod.DELETE, p.item(id), p.lead(), null, 204);
		List<String> actions = this.jdbc.queryForList("select action from portfolio_approvals where item_id = ? order by id", String.class, id);
		assertThat(actions).containsExactly("SUBMITTED", "CHANGES_REQUESTED", "SUBMITTED", "APPROVED", "FEATURED", "UNFEATURED", "ARCHIVED",
				"UNARCHIVED", "DELETED");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where item_id = ? and actor_id is null", Integer.class, id)).isZero();
	}

	@Test
	void historyIsAppendOnlyInTheDatabase() throws Exception {
		Pf p = pf("wf10");
		UUID id = submitted(p, p.student1(), "history");
		assertThatThrownBy(() -> this.jdbc.update("update portfolio_approvals set note = 'x' where item_id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> this.jdbc.update("delete from portfolio_approvals where item_id = ?", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where item_id = ?", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void noteIsVisibleToStudentAndReviewersNeverGuardians() throws Exception {
		Pf p = pf("wf11");
		cap(p, "CLASS");
		UUID id = id(create(p.student1(), p, textBody("noted", "x", "mode", "SUBMIT", "visibility", "CLASS"), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "REQUEST_CHANGES", "note", "NOTE-ONE"), 200);
		assertThat(get(p.item(id), p.student1()).get("reviewNote").asString()).isEqualTo("NOTE-ONE");
		assertThat(get(p.item(id), p.lead()).get("reviewNote").asString()).isEqualTo("NOTE-ONE");
		assertThat(get(p.item(id), p.admin()).get("reviewNote").asString()).isEqualTo("NOTE-ONE");
		post(p.item(id) + "/submit", p.student1(), null, 200);
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE", "note", "NOTE-TWO"), 200);
		assertThat(get(p.item(id), p.student1()).get("reviewNote").asString()).isEqualTo("NOTE-TWO");
		for (Persona reader : List.of(p.guardian1(), p.student2())) {
			JsonNode view = get(p.item(id), reader);
			assertThat(view.get("reviewNote")).as(reader.name()).isNull();
			assertThat(view.toString()).doesNotContain("NOTE-ONE", "NOTE-TWO");
		}
	}

	@Test
	void historyIsForStudentAndReviewersOnly() throws Exception {
		Pf p = pf("wf12");
		cap(p, "CLASS");
		UUID id = approved(p, p.student1(), "history", "CLASS");
		assertThat(get(p.item(id) + "/history", p.student1()).size()).isEqualTo(2);
		assertThat(get(p.item(id) + "/history", p.lead()).size()).isEqualTo(2);
		assertThat(get(p.item(id) + "/history", p.admin()).size()).isEqualTo(2);
		for (Persona other : List.of(p.guardian1(), p.student2(), p.assistant())) {
			assertThat(code(call(HttpMethod.GET, p.item(id) + "/history", other, null, 403))).as(other.name()).isEqualTo("portfolio.history_not_allowed");
		}
		assertThat(statusOf(HttpMethod.GET, p.item(id) + "/history", p.otherTeacher(), null)).isEqualTo(404);
		JsonNode history = get(p.item(id) + "/history", p.lead());
		assertThat(history.get(0).get("actorRole").asString()).isEqualTo("STUDENT");
		assertThat(history.get(0).get("actorName")).isNull();
		assertThat(history.get(1).get("actorRole").asString()).isEqualTo("TEACHER");
		assertThat(history.get(1).get("actorName").asString()).isNotBlank();
	}

	@Test
	void reviewersAreExactlyTheDefinedPeople() throws Exception {
		Pf p = pf("wf13");
		Persona manager = this.personas.withPlatformPermissions("class.manage", "school.read");
		Persona oversight = this.personas.withPlatformPermissions("school.portfolios.review", "school.read");
		Map<String, Persona> people = new LinkedHashMap<>();
		people.put("lead", p.lead());
		people.put("co", p.co());
		people.put("maths teacher (matching subject)", p.mathsTeacher());
		people.put("art teacher (other subject)", p.artTeacher());
		people.put("assistant", p.assistant());
		people.put("class manager", manager);
		people.put("oversight holder", oversight);
		people.put("school admin", p.admin());
		people.put("teacher of another class", p.otherTeacher());
		people.put("another school's admin", p.adminB());
		people.put("student", p.student1());
		people.put("classmate", p.student2());
		people.put("guardian", p.guardian1());
		Map<String, Integer> expected = new LinkedHashMap<>();
		expected.put("lead", 200);
		expected.put("co", 200);
		expected.put("maths teacher (matching subject)", 200);
		expected.put("art teacher (other subject)", 404);
		expected.put("assistant", 404);
		expected.put("class manager", 200);
		expected.put("oversight holder", 200);
		expected.put("school admin", 200);
		expected.put("teacher of another class", 404);
		expected.put("another school's admin", 404);
		expected.put("student", 403);
		expected.put("classmate", 404);
		expected.put("guardian", 404);
		for (Map.Entry<String, Persona> e : people.entrySet()) {
			UUID item = insertItem(p, p.s1(), "SUBMITTED", "GUARDIANS", p.classId(), p.mathsId());
			int status = statusOf(HttpMethod.POST, p.item(item) + "/review", e.getValue(), body("decision", "APPROVE"));
			assertThat(status).as(e.getKey()).isEqualTo(expected.get(e.getKey()));
		}
		// a subject teacher who does not review an item does not even see it while it waits; an item without a subject has no subject reviewer
		UUID noSubject = insertItem(p, p.s1(), "SUBMITTED", "GUARDIANS", p.classId(), null);
		assertThat(statusOf(HttpMethod.POST, p.item(noSubject) + "/review", p.mathsTeacher(), body("decision", "APPROVE"))).isEqualTo(404);
	}

	@Test
	void studentDeletesOnlyDraftSubmittedAndChangesRequested() throws Exception {
		Pf p = pf("wf14");
		for (PortfolioStatus s : PortfolioStatus.values()) {
			UUID item = insertItem(p, p.s1(), s.name(), "GUARDIANS", p.classId(), null);
			boolean allowed = s == PortfolioStatus.DRAFT || s == PortfolioStatus.SUBMITTED || s == PortfolioStatus.CHANGES_REQUESTED;
			if (allowed) {
				call(HttpMethod.DELETE, p.item(item), p.student1(), null, 204);
				assertThat(this.jdbc.queryForObject("select deleted_at is not null from portfolio_items where id = ?", Boolean.class, item)).isTrue();
			}
			else {
				assertThat(code(call(HttpMethod.DELETE, p.item(item), p.student1(), null, 403))).as(s.name()).isEqualTo("portfolio.delete_not_allowed");
				assertThat(this.jdbc.queryForObject("select deleted_at is null from portfolio_items where id = ?", Boolean.class, item)).isTrue();
			}
		}
		// somebody else's item: a classmate sees an approved class item but cannot delete it
		cap(p, "CLASS");
		UUID shared = approved(p, p.student1(), "shared", "CLASS");
		assertThat(code(call(HttpMethod.DELETE, p.item(shared), p.student2(), null, 403))).isEqualTo("portfolio.delete_not_allowed");
	}

	@Test
	void reviewerDeletesAnyStatusAndTheTextIsErased() throws Exception {
		Pf p = pf("wf15");
		UUID id = id(create(p.student1(), p, textBody("TITLE-CANARY", "TEXT-CANARY", "description", "DESC-CANARY", "reflection", "REFLECT-CANARY",
				"mode", "SUBMIT"), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		post(p.item(id) + "/comments", p.guardian1(), body("body", "COMMENT-CANARY"), 201);
		call(HttpMethod.DELETE, p.item(id), p.lead(), null, 204);
		Map<String, Object> row = this.jdbc.queryForMap("select title, description, reflection, text_content, deleted_by from portfolio_items where id = ?", id);
		assertThat(row.get("title")).isNull();
		assertThat(row.get("description")).isNull();
		assertThat(row.get("reflection")).isNull();
		assertThat(row.get("text_content")).isNull();
		assertThat(row.get("deleted_by")).isEqualTo(p.lead().userId());
		assertThat(this.jdbc.queryForObject("select body is null and status = 'DELETED' from portfolio_comments where item_id = ?", Boolean.class, id)).isTrue();
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where item_id = ? and action = 'DELETED'", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void deletedItemIsGoneForEveryoneAndItsMediaIsDiscarded() throws Exception {
		Pf p = pf("wf16");
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("pic", List.of(media), "mode", "SUBMIT"), 201));
		assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(200);
		call(HttpMethod.DELETE, p.item(id), p.student1(), null, 204);
		for (Persona who : List.of(p.student1(), p.lead(), p.admin(), p.guardian1())) {
			assertThat(statusOf(HttpMethod.GET, p.item(id), who, null)).as(who.name()).isEqualTo(404);
			assertThat(mediaUrlStatus(who, media)).as(who.name() + " media").isEqualTo(404);
		}
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, media)).isEqualTo("DELETED");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_attachments where item_id = ?", Integer.class, id)).isZero();
		assertThat(ids(get(p.api() + "/portfolio/mine", p.student1()))).doesNotContain(id.toString());
	}

	@Test
	void featuredOnlyOnApprovedItemsByReviewers() throws Exception {
		Pf p = pf("wf17");
		UUID submitted = submitted(p, p.student1(), "not yet");
		assertThat(code(call(HttpMethod.PUT, p.item(submitted) + "/featured", p.lead(), null, 409))).isEqualTo("portfolio.invalid_state");
		UUID id = approved(p, p.student1(), "star", "GUARDIANS");
		assertThat(code(call(HttpMethod.PUT, p.item(id) + "/featured", p.student1(), null, 403))).isEqualTo("portfolio.review_not_allowed");
		assertThat(statusOf(HttpMethod.PUT, p.item(id) + "/featured", p.guardian1(), null)).isEqualTo(403);
		assertThat(call(HttpMethod.PUT, p.item(id) + "/featured", p.lead(), null, 200).get("featured").asBoolean()).isTrue();
		call(HttpMethod.PUT, p.item(id) + "/featured", p.lead(), null, 200);
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where item_id = ? and action = 'FEATURED'", Integer.class, id)).isEqualTo(1);
		assertThat(call(HttpMethod.DELETE, p.item(id) + "/featured", p.co(), null, 200).get("featured").asBoolean()).isFalse();
		// archiving clears the mark
		call(HttpMethod.PUT, p.item(id) + "/featured", p.lead(), null, 200);
		post(p.item(id) + "/archive", p.lead(), body("note", "end of term"), 200);
		assertThat(this.jdbc.queryForObject("select is_featured from portfolio_items where id = ?", Boolean.class, id)).isFalse();
	}

	@Test
	void archiveHidesFromEveryoneButStudentAndReviewers() throws Exception {
		Pf p = pf("wf18");
		cap(p, "SCHOOL");
		UUID id = approved(p, p.student1(), "visible", "SCHOOL");
		for (Persona who : List.of(p.guardian1(), p.student2(), p.assistant(), p.otherStudent())) {
			assertThat(statusOf(HttpMethod.GET, p.item(id), who, null)).as(who.name() + " before").isEqualTo(200);
		}
		post(p.item(id) + "/archive", p.student1(), body(), 200);
		for (Persona who : List.of(p.guardian1(), p.student2(), p.assistant(), p.otherStudent(), p.otherTeacher())) {
			assertThat(statusOf(HttpMethod.GET, p.item(id), who, null)).as(who.name() + " after").isEqualTo(404);
		}
		for (Persona who : List.of(p.student1(), p.lead(), p.co(), p.admin())) {
			assertThat(get(p.item(id), who).get("status").asString()).as(who.name()).isEqualTo("ARCHIVED");
		}
		assertThat(ids(get(p.api() + "/portfolio/gallery", p.otherStudent()))).doesNotContain(id.toString());
		assertThat(ids(get("/api/v1/me/portfolio", p.guardian1()))).doesNotContain(id.toString());
		// a teacher archives somebody's item; only approved items can be archived
		UUID other = approved(p, p.student1(), "other", "GUARDIANS");
		post(p.item(other) + "/archive", p.lead(), body(), 200);
		assertThat(statusOf(HttpMethod.POST, p.item(id) + "/archive", p.student1(), body())).isEqualTo(409);
		assertThat(statusOf(HttpMethod.POST, p.item(draft(p, p.student1(), "d")) + "/archive", p.student1(), body())).isEqualTo(409);
	}

	@Test
	void unarchiveIsForReviewersAndRerunsTheGate() throws Exception {
		Pf p = pf("wf19");
		cap(p, "CLASS");
		grant(p.guardian1(), p.s1());
		UUID id = approvedPhoto(p, p.student1(), "gate", "CLASS");
		post(p.item(id) + "/archive", p.lead(), body(), 200);
		assertThat(code(post(p.item(id) + "/unarchive", p.student1(), body(), 403))).isEqualTo("portfolio.review_not_allowed");
		withdraw(p.guardian1(), p.s1());
		JsonNode refused = post(p.item(id) + "/unarchive", p.lead(), body(), 422);
		assertThat(code(refused)).isEqualTo("portfolio.media_consent_missing");
		assertThat(status(id)).isEqualTo("ARCHIVED");
		grant(p.guardian1(), p.s1());
		assertThat(post(p.item(id) + "/unarchive", p.lead(), body(), 200).get("status").asString()).isEqualTo("APPROVED");
		assertThat(this.jdbc.queryForObject("select count(*) from portfolio_approvals where item_id = ? and action = 'UNARCHIVED'", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void reviewQueueListsOnlySubmittedItemsForReviewers() throws Exception {
		Pf p = pf("wf20");
		UUID older = insertItem(p, p.s1(), "SUBMITTED", "GUARDIANS", p.classId(), p.mathsId());
		this.jdbc.update("update portfolio_items set submitted_at = now() - interval '2 hours' where id = ?", older);
		UUID newer = insertItem(p, p.s2(), "SUBMITTED", "GUARDIANS", p.classId(), p.artId());
		insertItem(p, p.s1(), "DRAFT", "GUARDIANS", p.classId(), null);
		insertItem(p, p.s1(), "APPROVED", "GUARDIANS", p.classId(), null);
		insertItem(p, p.s1(), "CHANGES_REQUESTED", "GUARDIANS", p.classId(), null);
		String queue = p.classApi() + "/portfolio/review-queue";
		assertThat(ids(get(queue, p.lead()))).containsExactly(older.toString(), newer.toString());
		assertThat(ids(get(queue, p.admin()))).containsExactly(older.toString(), newer.toString());
		// a subject teacher sees the items of their subject only
		assertThat(ids(get(queue, p.mathsTeacher()))).containsExactly(older.toString());
		assertThat(ids(get(queue, p.artTeacher()))).containsExactly(newer.toString());
		// keyset paging, oldest first
		JsonNode first = get(queue + "?limit=1", p.lead());
		assertThat(ids(first)).containsExactly(older.toString());
		JsonNode second = get(queue + "?limit=1&cursor=" + first.get("nextCursor").asString(), p.lead());
		assertThat(ids(second)).containsExactly(newer.toString());
		assertThat(second.get("nextCursor")).isNull();
		// everybody else: members who see the class get 403, the rest 404
		for (Persona who : List.of(p.assistant(), p.student1(), p.guardian1())) {
			assertThat(code(call(HttpMethod.GET, queue, who, null, 403))).as(who.name()).isEqualTo("portfolio.review_not_allowed");
		}
		for (Persona who : List.of(p.otherTeacher(), p.studentB(), p.adminB(), p.otherStudent())) {
			assertThat(statusOf(HttpMethod.GET, queue, who, null)).as(who.name()).isEqualTo(404);
		}
	}

}
