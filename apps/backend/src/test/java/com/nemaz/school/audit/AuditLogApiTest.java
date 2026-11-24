package com.nemaz.school.audit;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.audit.application.AuditPartitionMaintenance;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AuditLogApiTest {

	@Autowired
	AuditLog audit;

	@Autowired
	AuditPartitionMaintenance partitions;

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	Personas personas;

	@Test
	void writesInTheCallersTransaction() {
		String action = "test.rollback." + UUID.randomUUID();
		this.tx.executeWithoutResult((status) -> {
			this.audit.record(AuditEntry.of(action, AuditOutcome.SUCCESS));
			status.setRollbackOnly();
		});
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = ?", Integer.class, action))
			.isZero();
		this.tx.executeWithoutResult((status) -> this.audit.record(AuditEntry.of(action, AuditOutcome.FAILURE)
			.target("thing", UUID.randomUUID())
			.metadata("k", "v")
			.changes(java.util.Map.of("a", 1), java.util.Map.of("a", 2))));
		assertThat(this.jdbc.queryForObject("select changes->'after'->>'a' from audit_logs where action = ?", String.class,
				action)).isEqualTo("2");
	}

	@Test
	void platformAdministratorsPageThroughFilteredEntries() throws Exception {
		String prefix = "test.page." + UUID.randomUUID().toString().substring(0, 8);
		UUID target = UUID.randomUUID();
		this.tx.executeWithoutResult((status) -> {
			for (int i = 0; i < 5; i++) {
				this.audit.record(AuditEntry.of(prefix + ".item", AuditOutcome.SUCCESS).target("thing", target)
					.metadata("i", i));
			}
			this.audit.record(AuditEntry.of(prefix + ".other", AuditOutcome.DENIED).target("thing", UUID.randomUUID()));
		});
		var admin = this.personas.platformAdmin();
		List<Integer> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = this.json.readTree(this.mvc
				.perform(get("/api/v1/platform/audit-logs?limit=2&actionPrefix=" + prefix + "&targetId=" + target
						+ ((cursor != null) ? "&cursor=" + cursor : ""))
					.with(admin.bearer()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());
			page.get("items").forEach((item) -> seen.add(item.get("metadata").get("i").asInt()));
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
			pages++;
		}
		while (cursor != null && pages < 10);
		assertThat(seen).containsExactly(4, 3, 2, 1, 0);
		assertThat(pages).isEqualTo(3);

		this.mvc.perform(get("/api/v1/platform/audit-logs?actionPrefix=" + prefix).with(admin.bearer()))
			.andExpect(jsonPath("$.items", hasSize(6)))
			.andExpect(jsonPath("$.items[0].action").value(prefix + ".other"))
			.andExpect(jsonPath("$.items[0].outcome").value("DENIED"));
		this.mvc.perform(get("/api/v1/platform/audit-logs?actionPrefix=" + prefix + "&from=2000-01-01T00:00:00Z&to=2000-02-01T00:00:00Z")
			.with(admin.bearer())).andExpect(jsonPath("$.items", hasSize(0)));
		this.mvc.perform(get("/api/v1/platform/audit-logs?cursor=bad").with(admin.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_cursor"));
		this.mvc.perform(get("/api/v1/platform/audit-logs").with(this.personas.get(Name.COUNTRY_ADMIN_ET).bearer()))
			.andExpect(status().isForbidden());
	}

	@Test
	void mutationsRecordTheActorTheirRolesAndTheRequest() throws Exception {
		var admin = this.personas.platformAdmin();
		String slug = Personas.newSlug("audited");
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/platform/organizations")
			.with(admin.bearer())
			.header("X-Request-Id", "audit-test-request-1")
			.header("User-Agent", "AuditTest/1.0")
			.contentType("application/json")
			.content("{\"countryCode\":\"ET\",\"name\":\"Audited\",\"slug\":\"" + slug + "\"}"))
			.andExpect(status().isCreated());
		var row = this.jdbc.queryForMap("""
				select actor_user_id, actor_roles::text as roles, request_id, host(ip) as ip, user_agent, target_type
				from audit_logs where action = 'organization.create' and metadata->>'slug' = ?""", slug);
		assertThat(row.get("actor_user_id")).isEqualTo(admin.userId());
		assertThat(row.get("roles")).isEqualTo("{PLATFORM_ADMIN}");
		assertThat(row.get("request_id")).isEqualTo("audit-test-request-1");
		assertThat(row.get("ip")).isEqualTo("127.0.0.1");
		assertThat(row.get("user_agent")).isEqualTo("AuditTest/1.0");
		assertThat(row.get("target_type")).isEqualTo("organization");
	}

	@Test
	void partitionJobKeepsMonthsAheadAndIsIdempotent() {
		List<String> created = this.partitions.ensurePartitions(YearMonth.of(2031, 11));
		assertThat(created).containsExactly("audit_logs_y2031m11", "audit_logs_y2031m12", "audit_logs_y2032m01",
				"audit_logs_y2032m02");
		assertThat(this.partitions.ensurePartitions(YearMonth.of(2031, 11))).isEqualTo(created);
		for (String name : created) {
			assertThat(this.jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, name)).isTrue();
		}
		String partition = this.tx.execute((status) -> {
			this.jdbc.update("insert into audit_logs (occurred_at, action, outcome) values ('2032-01-15T12:00:00Z', 'test.future', 'SUCCESS')");
			return this.jdbc.queryForObject(
					"select tableoid::regclass::text from audit_logs where action = 'test.future' and occurred_at = '2032-01-15T12:00:00Z'",
					String.class);
		});
		assertThat(partition).isEqualTo("audit_logs_y2032m01");
		// the current month and the next three always exist (startup run)
		assertThat(this.partitions.ensurePartitions()).hasSize(4)
			.allSatisfy((name) -> assertThat(this.jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class,
					name)).isTrue());
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs_default", Integer.class)).isZero();
	}

}
