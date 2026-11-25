package com.nemaz.school.engagement;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Undo: the REVERSAL row, the undo window, who may reverse when, batches and races. */
@IntegrationTest
class PointReversalApiTest extends PointsTestSupport {

	private JsonNode reverse(Setup s, com.nemaz.school.support.Persona as, UUID transactionId, Map<String, Object> body, int status)
			throws Exception {
		return call(HttpMethod.POST, s.api() + "/points/transactions/" + transactionId + "/reverse", as, body, status);
	}

	@Test
	void theAwarderUndoesWithinTheWindowAndTheLedgerKeepsBothRows() throws Exception {
		Setup s = setup("Undo school");
		JsonNode award = award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		UUID tx = transactionId(award, 0);
		awaitClassTotal(s.classId(), s.s1(), 1);

		JsonNode undo = reverse(s, s.lead(), tx, Map.of("note", "Wrong child"), 201);
		JsonNode row = undo.get("transactions").get(0);
		assertThat(row.get("type").asString()).isEqualTo("REVERSAL");
		assertThat(row.get("points").asInt()).isEqualTo(-1);
		assertThat(uuid(row, "reversesTransactionId")).isEqualTo(tx);
		assertThat(ledgerRows("student_id = ?", s.s1())).isEqualTo(2);
		awaitClassTotal(s.classId(), s.s1(), 0);
		// once only, and a reversal is not reversible
		assertThat(errorCode(reverse(s, s.lead(), tx, null, 409))).isEqualTo("points.already_reversed");
		assertThat(errorCode(reverse(s, s.lead(), uuid(row, "id"), null, 409))).isEqualTo("points.not_reversible");
		// the timeline says so
		JsonNode timeline = get(s.api() + "/students/" + s.s1() + "/points/transactions", s.lead());
		assertThat(timeline.get("items")).hasSize(2);
		assertThat(timeline.get("items").findValuesAsString("type")).containsExactlyInAnyOrder("AWARD", "REVERSAL");
		assertThat(timeline.get("items").findValues("reversed").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);
	}

	@Test
	void aNeedsWorkAwardIsReversedByAPositiveRow() throws Exception {
		Setup s = setup("Undo needs work school");
		JsonNode award = award(s, s.lead(), category(s, "disruptive"), List.of(s.s1()), 201);
		JsonNode undo = reverse(s, s.lead(), transactionId(award, 0), null, 201);
		assertThat(undo.get("transactions").get(0).get("points").asInt()).isEqualTo(1);
		awaitClassTotal(s.classId(), s.s1(), 0);
	}

	@Test
	void afterTheWindowOnlyTheLeadTeacherAndAdministratorsCanUndo() throws Exception {
		Setup s = setup("Window school");
		UUID kindness = category(s, "kindness");
		UUID byAssistant = transactionId(award(s, s.assistant(), kindness, List.of(s.s1()), 201), 0);
		UUID byAssistantToo = transactionId(award(s, s.assistant(), kindness, List.of(s.s2()), 201), 0);
		UUID byLead = transactionId(award(s, s.lead(), kindness, List.of(s.s2()), 201), 0);

		// within the window the assistant (not a lead) can undo only their own award
		assertThat(errorCode(reverse(s, s.assistant(), byLead, null, 403))).isEqualTo("points.reversal_not_allowed");
		reverse(s, s.assistant(), byAssistantToo, null, 201);

		this.clock.advance(Duration.ofMinutes(3));
		assertThat(errorCode(reverse(s, s.assistant(), byAssistant, null, 403))).isEqualTo("points.undo_window_expired");
		// the lead, any time, and an administrator
		reverse(s, s.lead(), byAssistant, null, 201);
		JsonNode byAdmin = reverse(s, s.admin(), byLead, null, 201);
		assertThat(this.jdbc.queryForObject("select awarder_role from point_transactions where id = ?::uuid", String.class,
				byAdmin.get("transactions").get(0).get("id").asString())).isEqualTo("SCHOOL_ADMIN");
	}

	@Test
	void theUndoWindowIsASchoolSetting() throws Exception {
		Setup s = setup("Window setting school");
		UUID kindness = category(s, "kindness");
		setting(s.schoolId(), "engagement.points.undo_window_seconds", 0);
		JsonNode award = award(s, s.assistant(), kindness, List.of(s.s1()), 201);
		assertThat(absent(award, "undoableUntil")).isTrue();
		assertThat(errorCode(reverse(s, s.assistant(), transactionId(award, 0), null, 403))).isEqualTo("points.undo_window_expired");
		setting(s.schoolId(), "engagement.points.undo_window_seconds", 600);
		JsonNode longer = award(s, s.assistant(), kindness, List.of(s.s1()), 201);
		this.clock.advance(Duration.ofMinutes(5));
		reverse(s, s.assistant(), transactionId(longer, 0), null, 201);
	}

	@Test
	void strangersCannotEvenFindTheTransaction() throws Exception {
		Setup s = setup("Stranger undo school");
		UUID tx = transactionId(award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201), 0);
		Setup elsewhere = setup("Stranger elsewhere school");
		for (var stranger : List.of(s.student1(), s.student2(), s.guardian(), s.otherTeacher(), elsewhere.admin())) {
			assertThat(errorCode(reverse(s, stranger, tx, null, 404))).isEqualTo("points.transaction_not_found");
		}
		assertThat(errorCode(reverse(s, s.lead(), Ids.newId(), null, 404))).isEqualTo("points.transaction_not_found");
		assertThat(ledgerRows("student_id = ?", s.s1())).isEqualTo(1);
	}

	@Test
	void aWholeBatchIsReversedTogetherAndOnlyOnce() throws Exception {
		Setup s = setup("Batch undo school");
		JsonNode whole = call(HttpMethod.POST, s.classApi() + "/points/awards", s.assistant(),
				Map.of("categoryId", category(s, "kindness"), "wholeClass", true), 201);
		UUID batch = uuid(whole, "batchId");
		String url = s.api() + "/points/batches/" + batch + "/reverse";
		awaitClassTotal(s.classId(), s.s1(), 1);

		this.clock.advance(Duration.ofMinutes(3));
		assertThat(errorCode(call(HttpMethod.POST, url, s.assistant(), null, 403))).isEqualTo("points.undo_window_expired");
		call(HttpMethod.POST, url, s.student1(), null, 404);
		UUID op = Ids.newId();
		JsonNode undo = call(HttpMethod.POST, url, s.lead(), Map.of("clientOpId", op), 201);
		assertThat(undo.get("transactions")).hasSize(2);
		assertThat(undo.get("transactions").findValuesAsString("type")).containsOnly("REVERSAL");
		JsonNode replay = call(HttpMethod.POST, url, s.lead(), Map.of("clientOpId", op), 200);
		assertThat(replay.get("replayed").asBoolean()).isTrue();
		assertThat(errorCode(call(HttpMethod.POST, url, s.lead(), null, 409))).isEqualTo("points.already_reversed");
		awaitClassTotal(s.classId(), s.s1(), 0);
		awaitClassTotal(s.classId(), s.s2(), 0);
		call(HttpMethod.POST, s.api() + "/points/batches/" + Ids.newId() + "/reverse", s.lead(), null, 404);
	}

	@Test
	void reversingOneRowOfABatchLeavesTheOthersForTheBatchReversal() throws Exception {
		Setup s = setup("Partial batch school");
		JsonNode two = award(s, s.lead(), category(s, "kindness"), List.of(s.s1(), s.s2()), 201);
		reverse(s, s.lead(), transactionId(two, 0), null, 201);
		JsonNode rest = call(HttpMethod.POST, s.api() + "/points/batches/" + uuid(two, "batchId") + "/reverse", s.lead(), null, 201);
		assertThat(rest.get("transactions")).hasSize(1);
	}

	@Test
	void parallelReversalsOfOneTransactionWriteOneRow() throws Exception {
		Setup s = setup("Race undo school");
		UUID tx = transactionId(award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201), 0);
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				futures.add(pool.submit(() -> perform(HttpMethod.POST, s.api() + "/points/transactions/" + tx + "/reverse", s.lead(),
						null)
					.andReturn()
					.getResponse()
					.getStatus()));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get());
			}
			assertThat(statuses.stream().filter((x) -> x == 201).count()).isEqualTo(1);
			assertThat(statuses.stream().filter((x) -> x == 409).count()).isEqualTo(5);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(ledgerRows("reverses_transaction_id = ?", tx)).isEqualTo(1);
		awaitClassTotal(s.classId(), s.s1(), 0);
	}

	@Test
	void homePointsAreUndoneOnlyByTheAwardingGuardianInsideTheWindow() throws Exception {
		Setup s = setup("Home undo school");
		UUID homework = category(s, "home_homework");
		UUID mistaken = transactionId(awardHome(s, s.guardian(), s.s1(), homework, 201), 0);
		UUID later = transactionId(awardHome(s, s.guardian(), s.s1(), homework, 201), 0);
		// the school never reaches into home points, not even the lead teacher or an administrator
		for (var school : List.of(s.lead(), s.admin(), s.student1())) {
			assertThat(errorCode(reverse(s, school, mistaken, null, 404))).isEqualTo("points.transaction_not_found");
		}
		JsonNode undo = reverse(s, s.guardian(), mistaken, null, 201);
		assertThat(this.jdbc.queryForObject("select awarder_role from point_transactions where id = ?::uuid", String.class,
				undo.get("transactions").get(0).get("id").asString())).isEqualTo("GUARDIAN");
		this.clock.advance(Duration.ofMinutes(3));
		assertThat(errorCode(reverse(s, s.guardian(), later, null, 403))).isEqualTo("points.undo_window_expired");
	}

}
