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
import com.nemaz.school.engagement.application.BalanceMaintenance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The balance projection: concurrency, home exclusion, idempotent delivery and the nightly repair. */
@IntegrationTest
class PointBalancesTest extends PointsTestSupport {

	@Autowired
	BalanceMaintenance maintenance;

	@Autowired
	PointsApi pointsApi;

	@Autowired
	ApplicationEventPublisher events;

	@Test
	void parallelAwardsGiveCorrectBalances() throws Exception {
		Setup s = setup("Parallel balances school");
		UUID kindness = category(s, "kindness");
		UUID persistence = category(s, "persistence");
		UUID disruptive = category(s, "disruptive");
		ExecutorService pool = Executors.newFixedThreadPool(12);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 30; i++) {
				UUID category = (i % 3 == 0) ? persistence : (i % 3 == 1) ? kindness : disruptive;
				List<UUID> students = (i % 2 == 0) ? List.of(s.s1()) : List.of(s.s1(), s.s2());
				futures.add(pool.submit(() -> perform(HttpMethod.POST, s.classApi() + "/points/awards", s.lead(),
						Map.of("categoryId", category, "studentIds", students))
					.andReturn()
					.getResponse()
					.getStatus()));
			}
			for (Future<Integer> f : futures) {
				assertThat(f.get()).isEqualTo(201);
			}
		}
		finally {
			pool.shutdownNow();
		}
		// persistence +2 (i%3==0), kindness +1 (i%3==1), disruptive -1 (i%3==2): s1 gets all 30, s2 the odd ones
		int expectedS1 = 0;
		int expectedS2 = 0;
		for (int i = 0; i < 30; i++) {
			int points = (i % 3 == 0) ? 2 : (i % 3 == 1) ? 1 : -1;
			expectedS1 += points;
			if (i % 2 == 1) {
				expectedS2 += points;
			}
		}
		assertThat(this.jdbc.queryForObject("select sum(points) from point_transactions where student_id = ?", Integer.class, s.s1()))
			.isEqualTo(expectedS1);
		awaitClassTotal(s.classId(), s.s1(), expectedS1);
		awaitClassTotal(s.classId(), s.s2(), expectedS2);
		assertThat(this.pointsApi.classTotal(s.s1(), s.classId())).isEqualTo(expectedS1);
	}

	@Test
	void homePointsHaveTheirOwnBalanceAndNeverReachTheClassOrSchoolTotals() throws Exception {
		Setup s = setup("Home balance school");
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		call(HttpMethod.POST, s.api() + "/students/" + s.s1() + "/home-points", s.guardian(),
				Map.of("categoryId", category(s, "home_homework"), "points", 10), 201);
		awaitClassTotal(s.classId(), s.s1(), 1);
		await().untilAsserted(() -> assertThat(this.jdbc.queryForList(
				"select positive_total from point_balances where student_id = ? and context = 'HOME'", Integer.class, s.s1()))
			.containsExactly(10));
		assertThat(this.pointsApi.classTotal(s.s1(), s.classId())).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("""
				select coalesce(sum(positive_total + needs_work_total), 0) from point_balances
				where school_id = ? and context in ('CLASS', 'SCHOOL')""", Integer.class, s.schoolId())).isEqualTo(1);
	}

	@Test
	void redeliveringAnEventChangesNothing() throws Exception {
		Setup s = setup("Redelivery school");
		JsonNode result = award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		awaitClassTotal(s.classId(), s.s1(), 1);
		UUID tx = transactionId(result, 0);
		var row = this.jdbc.queryForMap("select * from point_transactions where id = ?", tx);
		for (int i = 0; i < 3; i++) {
			this.tx.executeWithoutResult((x) -> this.events.publishEvent(new PointTransactionRecorded(tx, s.schoolId(), s.s1(),
					PointContext.CLASS, s.classId(), (UUID) row.get("academic_year_id"), (UUID) row.get("category_id"),
					PointPolarity.POSITIVE, PointTransactionType.AWARD, 1, s.leadId())));
		}
		Thread.sleep(1500);
		awaitClassTotal(s.classId(), s.s1(), 1);
	}

	@Test
	void theNightlyJobRepairsDriftFromTheLedger() throws Exception {
		Setup s = setup("Repair school");
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1(), s.s2()), 201);
		award(s, s.lead(), category(s, "persistence"), List.of(s.s1()), 201);
		awaitClassTotal(s.classId(), s.s1(), 3);
		awaitClassTotal(s.classId(), s.s2(), 1);
		await().untilAsserted(() -> assertThat(this.maintenance.repair()).isZero());

		this.jdbc.update("update point_balances set positive_total = 999 where student_id = ? and class_id = ?", s.s1(), s.classId());
		this.jdbc.update("delete from point_balances where student_id = ? and class_id = ?", s.s2(), s.classId());
		UUID ghostYear = this.personas.currentYear(s.schoolId());
		this.jdbc.update("""
				insert into point_balances (student_id, school_id, academic_year_id, context, class_id, positive_total)
				values (?, ?, ?, 'SCHOOL', '00000000-0000-0000-0000-000000000000', 7)""", s.s2(), s.schoolId(), ghostYear);

		assertThat(this.maintenance.repair()).isGreaterThanOrEqualTo(3);
		awaitClassTotal(s.classId(), s.s1(), 3);
		awaitClassTotal(s.classId(), s.s2(), 1);
		assertThat(this.jdbc.queryForObject("""
				select positive_total from point_balances where student_id = ? and context = 'SCHOOL'""", Integer.class, s.s2())).isZero();
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(this.maintenance.repair()).isZero());
	}

	@Test
	void aMissingBalanceIsRebuiltFromTheLedger() throws Exception {
		Setup s = setup("Rebuild school");
		award(s, s.lead(), category(s, "kindness"), List.of(s.s1()), 201);
		awaitClassTotal(s.classId(), s.s1(), 1);
		await().untilAsserted(() -> assertThat(this.maintenance.repair()).isZero());
		this.jdbc.update("delete from point_balances where school_id = ?", s.schoolId());
		assertThat(this.maintenance.repair()).isGreaterThanOrEqualTo(1);
		awaitClassTotal(s.classId(), s.s1(), 1);
	}

	@Test
	void systemAwardsAreIdempotentPerOperationAndUseTheSchoolsCategoryCopy() throws Exception {
		Setup s = setup("System award school");
		category(s, "kindness");
		UUID op = Ids.newId();
		UUID first = this.tx.execute((x) -> this.pointsApi.awardAsSystem(s.schoolId(), s.s1(), s.classId(), "kindness", op));
		UUID second = this.tx.execute((x) -> this.pointsApi.awardAsSystem(s.schoolId(), s.s1(), s.classId(), "kindness", op));
		assertThat(second).isEqualTo(first);
		assertThat(ledgerRows("student_id = ? and awarder_role = 'SYSTEM' and awarded_by is null", s.s1())).isEqualTo(1);
		awaitClassTotal(s.classId(), s.s1(), 1);
	}

}
