package com.nemaz.school.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.id.Ids;
import com.nemaz.school.messaging.MessagingTestSupport;
import com.nemaz.school.support.Persona;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

/**
 * A school with the people of the messaging fixture ({@code Msg}) plus posts, comments and stories written by SQL (the feed API
 * is not what these tests are about) and the calls of the moderation API. Every call goes through the real HTTP layer.
 */
public abstract class ModerationTestSupport extends MessagingTestSupport {

	protected static final String REPORTS = "/api/v1/moderation/reports";

	protected static final String CASES = "/api/v1/moderation/cases/";

	// --- fixtures -----------------------------------------------------------------------------------------------------

	/** A PUBLISHED post of the school's class 1A written by {@code author}. */
	protected UUID post(Msg m, UUID author, String role, String body) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into posts (id, scope_type, school_id, class_id, author_id, author_role, status, published_at, body,
				                   created_at, updated_at)
				values (?, 'CLASS', ?, ?, ?, ?, 'PUBLISHED', now(), ?, now(), now())""", id, m.schoolId(), m.classId(), author, role, body);
		return id;
	}

	protected UUID comment(Msg m, UUID post, UUID author, String role, String body) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into post_comments (id, post_id, school_id, author_id, author_role, body) values (?, ?, ?, ?, ?, ?)""", id, post,
				m.schoolId(), author, role, body);
		return id;
	}

	protected UUID story(Msg m, UUID author, String caption) {
		UUID id = Ids.newId();
		this.jdbc.update("""
				insert into stories (id, scope_type, school_id, class_id, author_id, author_role, caption, status, published_at, expires_at)
				values (?, 'CLASS', ?, ?, ?, 'TEACHER', ?, 'PUBLISHED', now(), now() + interval '1 day')""", id, m.schoolId(), m.classId(), author,
				caption);
		return id;
	}

	protected Persona safeguardingLead(Msg m, String label) {
		return this.personas.safeguardingLead(m.schoolId(), label);
	}

	// --- calls --------------------------------------------------------------------------------------------------------

	protected static Map<String, Object> reportBody(String type, UUID id, String reason, String details) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("targetType", type);
		body.put("targetId", id);
		body.put("reasonCode", reason);
		if (details != null) {
			body.put("details", details);
		}
		return body;
	}

	/** Files a report and expects {@code status} (201 for new, 200 for a repeat); returns the body. */
	protected JsonNode report(Persona who, String type, UUID id, String reason, String details, int status) throws Exception {
		return call(HttpMethod.POST, REPORTS, who, reportBody(type, id, reason, details), status);
	}

	protected JsonNode report(Persona who, String type, UUID id, String reason) throws Exception {
		return report(who, type, id, reason, null, 201);
	}

	/** The open case of a target (straight from the database: ids only). */
	protected UUID caseOf(String type, UUID target) {
		return this.jdbc.queryForObject("select id from moderation_cases where target_type = ? and target_id = ? order by created_at desc limit 1",
				UUID.class, type, target);
	}

	protected Map<String, Object> row(UUID caseId) {
		return this.jdbc.queryForMap("select * from moderation_cases where id = ?", caseId);
	}

	protected String schoolQueue(Msg m) {
		return "/api/v1/schools/" + m.schoolId() + "/moderation/cases";
	}

	protected JsonNode decide(Persona who, UUID caseId, String decision, String reason, int status) throws Exception {
		return call(HttpMethod.POST, CASES + caseId + "/decision", who, body("decision", decision, "reason", reason), status);
	}

	protected static final String REASON = "Reviewed by the moderator, see the school rules";

	protected JsonNode detail(Persona who, UUID caseId, int status) throws Exception {
		return call(HttpMethod.GET, CASES + caseId, who, null, status);
	}

	/** The ids of the cases in a queue answer. */
	protected List<String> queueIds(JsonNode page) {
		List<String> ids = new java.util.ArrayList<>();
		page.get("items").forEach((c) -> ids.add(c.get("id").asString()));
		return ids;
	}

	protected int notificationsOf(UUID user, String type) {
		Integer n = this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = ?", Integer.class, user, type);
		return (n == null) ? 0 : n;
	}

	protected Timestamp ago(java.time.Period period) {
		return Timestamp.from(Instant.now().atZone(java.time.ZoneOffset.UTC).minus(period).toInstant());
	}

	protected void assertNoCase(String type, UUID target) {
		assertThat(this.jdbc.queryForObject("select count(*) from moderation_cases where target_type = ? and target_id = ?", Integer.class,
				type, target)).isZero();
	}

}
