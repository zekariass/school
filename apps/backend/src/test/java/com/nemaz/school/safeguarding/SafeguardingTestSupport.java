package com.nemaz.school.safeguarding;

import java.util.UUID;

import com.nemaz.school.moderation.ModerationTestSupport;
import com.nemaz.school.support.Persona;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

/** The calls of the safeguarding API as a person makes them. */
public abstract class SafeguardingTestSupport extends ModerationTestSupport {

	protected static final String NARRATIVE = "CANARY-NARRATIVE: the child told a teacher that something happened after school";

	protected String concerns(Msg m) {
		return "/api/v1/schools/" + m.schoolId() + "/safeguarding/concerns";
	}

	protected String incidents(Msg m) {
		return "/api/v1/schools/" + m.schoolId() + "/safeguarding/incidents";
	}

	protected static String incident(UUID id) {
		return "/api/v1/safeguarding/incidents/" + id;
	}

	/** Raises a concern and expects 201; returns the incident id. */
	protected UUID raise(Persona who, Msg m, String category, String severity, String narrative) throws Exception {
		JsonNode created = call(HttpMethod.POST, concerns(m), who, body("category", category, "severity", severity, "narrative", narrative), 201);
		return UUID.fromString(created.get("id").asString());
	}

	protected UUID raise(Persona who, Msg m, String severity) throws Exception {
		return raise(who, m, "CONDUCT", severity, NARRATIVE);
	}

	protected JsonNode open(Persona who, UUID incident, int status) throws Exception {
		return call(HttpMethod.POST, incident(incident) + "/open", who, body("reason", "TRIAGE"), status);
	}

	protected JsonNode open(Persona who, UUID incident) throws Exception {
		return open(who, incident, 200);
	}

	protected String envelope(UUID incident) {
		return this.jdbc.queryForObject("select narrative_enc from safeguarding_incidents where id = ?", String.class, incident);
	}

}
