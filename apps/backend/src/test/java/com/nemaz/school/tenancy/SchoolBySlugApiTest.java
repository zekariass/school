package com.nemaz.school.tenancy;

import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.Personas.Fixture;
import com.nemaz.school.support.Personas.Name;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Resolving a school by slug, and the slug/name in {@code GET /me} grants. */
@IntegrationTest
class SchoolBySlugApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Personas personas;

	private String slugOf(UUID schoolId) {
		return this.jdbc.queryForObject("select slug from schools where id = ?", String.class, schoolId);
	}

	@Test
	void readersGetTheSchoolAndEveryoneElseTheSame404AsForAnUnknownSlug() throws Exception {
		Fixture f = this.personas.fixture();
		String slug = slugOf(f.schoolA());
		this.mvc.perform(get("/api/v1/schools/by-slug/" + slug).with(this.personas.get(Name.SCHOOL_ADMIN_A).bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(f.schoolA().toString()))
			.andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.name").value("Persona School A"))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		this.mvc.perform(get("/api/v1/schools/by-slug/" + slug).with(this.personas.get(Name.CAMPUS_ADMIN_A).bearer()))
			.andExpect(status().isOk());

		String forbidden = this.mvc.perform(get("/api/v1/schools/by-slug/" + slug)
			.with(this.personas.get(Name.SCHOOL_ADMIN_B).bearer())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("school.not_found")).andReturn().getResponse().getContentAsString();
		String unknown = this.mvc.perform(get("/api/v1/schools/by-slug/no-such-school")
			.with(this.personas.get(Name.SCHOOL_ADMIN_B).bearer())).andExpect(status().isNotFound())
			.andReturn().getResponse().getContentAsString();
		assertThat(strip(forbidden)).isEqualTo(strip(unknown));
		this.mvc.perform(get("/api/v1/schools/by-slug/" + slug).with(this.personas.plainUser().bearer()))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/v1/schools/by-slug/" + slug)).andExpect(status().isUnauthorized());
	}

	/** Problem bodies differ only in request-specific fields. */
	private static String strip(String problem) {
		return problem.replaceAll("\"(instance|requestId|timestamp)\":\"[^\"]*\",?", "");
	}

	@Test
	void meGrantsCarryTheSchoolSlugAndName() throws Exception {
		Fixture f = this.personas.fixture();
		Persona admin = this.personas.get(Name.SCHOOL_ADMIN_A);
		this.mvc.perform(get("/api/v1/me").with(admin.bearer()))
			.andExpect(jsonPath("$.roleAssignments[0].schoolSlug").value(slugOf(f.schoolA())))
			.andExpect(jsonPath("$.roleAssignments[0].schoolName").value("Persona School A"))
			.andExpect(jsonPath("$.scopes[0].schoolSlug").value(slugOf(f.schoolA())));
		// a campus grant resolves its school
		this.mvc.perform(get("/api/v1/me").with(this.personas.get(Name.CAMPUS_ADMIN_A).bearer()))
			.andExpect(jsonPath("$.roleAssignments[0].schoolSlug").value(slugOf(f.schoolA())))
			.andExpect(jsonPath("$.roleAssignments[0].schoolName").value("Persona School A"))
			.andExpect(jsonPath("$.roleAssignments[0].scopeName").value("Campus A"));
		// platform grants have none
		this.mvc.perform(get("/api/v1/me").with(this.personas.platformAdmin().bearer()))
			.andExpect(jsonPath("$.roleAssignments[0].schoolSlug").doesNotExist());
	}

	@Test
	void definitionsAndPlansAreServedLocalized() throws Exception {
		this.mvc.perform(get("/api/v1/platform/definitions").with(this.personas.platformAdmin().bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.features[?(@.key=='feed')].defaultEnabled").value(true))
			.andExpect(jsonPath("$.settings[?(@.key=='engagement.badges.enabled')].valueSchema.type").value("boolean"))
			.andExpect(jsonPath("$.settings[0].allowedScopes").isArray());
		this.mvc.perform(get("/api/v1/platform/plans").with(this.personas.platformAdmin().bearer())
			.header("Accept-Language", "am"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.code=='SCHOOL_FREE')].name").value("ነፃ"));
		this.mvc.perform(get("/api/v1/platform/plans").with(this.personas.platformAdmin().bearer()))
			.andExpect(jsonPath("$[?(@.code=='SCHOOL_BASIC')].name").value("Basic"));
	}

}
