package com.nemaz.school.tenancy;

import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.identity.application.PhoneInputs;
import com.nemaz.school.support.ImportTestSupport;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Docs/Spec_Review.md D42: country configuration comes from the database, several countries run at once. Kenya is seeded as
 * DRAFT; the tests enable it (and give it its own policy text where a test needs one) and put everything back.
 */
@IntegrationTest
class CountryConfigTest extends ImportTestSupport {

	@Autowired
	CountryResolver resolver;

	@Autowired
	SettingsService settings;

	@Autowired
	PhoneInputs phones;

	private Persona platformAdmin;

	@BeforeEach
	void enableKenya() {
		this.jdbc.update("update countries set status = 'ENABLED' where code = 'KE'");
		this.platformAdmin = this.personas.platformAdmin();
	}

	@AfterEach
	void restoreCountries() {
		this.jdbc.update("delete from settings where key = 'platform.default_country'");
		this.jdbc.update("update countries set status = 'ENABLED' where code = 'ET'");
		this.jdbc.update("update countries set status = 'DRAFT' where code = 'KE'");
		this.jdbc.update("delete from policy_versions where policy_document_id in (select id from policy_documents where country_code = 'KE')");
		this.jdbc.update("delete from policy_documents where country_code = 'KE'");
	}

	// --- /meta ------------------------------------------------------------------------------

	@Test
	void metaListsTheEnabledCountriesWithTheirConfigurationInTheRequestLanguage() throws Exception {
		this.mvc.perform(get("/api/v1/meta"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.defaultCountry").value("ET"))
			.andExpect(jsonPath("$.supportedLocales[0]").value("en"))
			.andExpect(jsonPath("$.countries[?(@.code == 'ET')].phoneCountryCode").value("+251"))
			.andExpect(jsonPath("$.countries[?(@.code == 'ET')].defaultTimezone").value("Africa/Addis_Ababa"))
			.andExpect(jsonPath("$.countries[?(@.code == 'ET')].defaultCalendarCode").value("ETHIOPIAN"))
			.andExpect(jsonPath("$.countries[?(@.code == 'ET')].calendarCodes[0]").value("ETHIOPIAN"))
			.andExpect(jsonPath("$.countries[?(@.code == 'ET')].languageCodes[0]").value("am"))
			.andExpect(jsonPath("$.countries[?(@.code == 'ET')].name").value("Ethiopia"))
			.andExpect(jsonPath("$.countries[?(@.code == 'KE')].phoneCountryCode").value("+254"))
			.andExpect(jsonPath("$.countries[?(@.code == 'KE')].defaultTimezone").value("Africa/Nairobi"))
			.andExpect(jsonPath("$.countries[?(@.code == 'KE')].calendarCodes[0]").value("GREGORIAN"))
			.andExpect(jsonPath("$.countries[?(@.code == 'KE')].currencyCode").value("KES"));
		this.mvc.perform(get("/api/v1/meta").header("Accept-Language", "am"))
			.andExpect(jsonPath("$.countries[?(@.code == 'KE')].name").value("ኬንያ"));

		// a country that is not ENABLED is not offered
		this.jdbc.update("update countries set status = 'DRAFT' where code = 'KE'");
		this.mvc.perform(get("/api/v1/meta")).andExpect(jsonPath("$.countries[?(@.code == 'KE')]").isEmpty());
	}

	// --- the platform default -----------------------------------------------------------------

	@Test
	void thePlatformDefaultIsADatabaseSettingThatMustNameAnEnabledCountry() throws Exception {
		assertThat(this.resolver.platformDefault().code()).isEqualTo("ET");
		SettingValue seeded = this.settings.resolve(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform());
		assertThat(seeded.source()).isEqualTo(ConfigSource.DEFAULT);

		// refused while the country is not enabled, or unknown
		this.jdbc.update("update countries set status = 'DRAFT' where code = 'KE'");
		assertThatThrownBy(() -> this.settings.set(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform(),
				this.json.valueToTree("KE"), this.platformAdmin.userId()))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("country.not_enabled"));
		assertThatThrownBy(() -> this.settings.set(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform(),
				this.json.valueToTree("ZZ"), this.platformAdmin.userId()))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("country.unknown"));
		// only the platform may set it
		assertThatThrownBy(() -> this.settings.set(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.country("ET"),
				this.json.valueToTree("ET"), this.platformAdmin.userId()))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("setting.scope_not_allowed"));

		// accepted once enabled: flows without a school (phone sign-in) now read national numbers in Kenya
		this.jdbc.update("update countries set status = 'ENABLED' where code = 'KE'");
		this.settings.set(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform(), this.json.valueToTree("KE"),
				this.platformAdmin.userId());
		assertThat(this.resolver.platformDefault().code()).isEqualTo("KE");
		assertThat(this.resolver.platformDefault().zone()).isEqualTo(ZoneId.of("Africa/Nairobi"));
		this.mvc.perform(get("/api/v1/meta")).andExpect(jsonPath("$.defaultCountry").value("KE"));
		assertThat(this.phones.toE164("0712345678", null)).contains("+254712345678");
		assertThat(this.phones.toE164("0712345678", "ET")).contains("+251712345678");
		assertThat(this.phones.toE164("+251911234567", null)).as("a plus prefix names its own country").contains("+251911234567");
		// a school or a user home country still comes first
		assertThat(this.resolver.forUser("ET").code()).isEqualTo("ET");

		// and the default can be cleared back to the seed
		this.settings.clear(CountryResolver.PLATFORM_DEFAULT_KEY, SettingScope.platform(), this.platformAdmin.userId());
		assertThat(this.resolver.platformDefault().code()).isEqualTo("ET");
	}

	@Test
	void aPlatformDefaultThatIsNoLongerEnabledFailsLoudlyInsteadOfFallingBack() {
		this.jdbc.update("update countries set status = 'DISABLED' where code = 'ET'");
		assertThatThrownBy(() -> this.resolver.platformDefault()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("platform.default_country")
			.hasMessageContaining("not ENABLED");
	}

	// --- a school in another country ------------------------------------------------------------

	@Test
	void aKenyanSchoolGetsItsOwnZoneCalendarsAndPhoneRulesNextToAnEthiopianOne() throws Exception {
		UUID kenya = this.personas.newSchoolIn("KE", "Nairobi school");
		UUID ethiopia = this.personas.newSchool("Addis school");
		Persona admin = this.personas.schoolAdmin(kenya, "nairobi-admin");

		assertThat(this.resolver.forSchool(kenya).code()).isEqualTo("KE");
		assertThat(this.resolver.schoolZone(kenya)).isEqualTo(ZoneId.of("Africa/Nairobi"));
		assertThat(this.resolver.schoolZone(ethiopia)).isEqualTo(ZoneId.of("Africa/Addis_Ababa"));
		assertThat(this.resolver.forSchool(kenya).hasCalendar("ETHIOPIAN")).isFalse();
		assertThat(this.resolver.forSchool(ethiopia).hasCalendar("ETHIOPIAN")).isTrue();

		// the workspace reads the school's country configuration
		String slug = this.jdbc.queryForObject("select slug from schools where id = ?", String.class, kenya);
		JsonNode summary = call(HttpMethod.GET, "/api/v1/schools/by-slug/" + slug, admin, null, 200);
		assertThat(summary.get("countryCode").asString()).isEqualTo("KE");
		assertThat(summary.get("timezone").asString()).isEqualTo("Africa/Nairobi");
		assertThat(summary.get("defaultCalendarCode").asString()).isEqualTo("GREGORIAN");
		assertThat(summary.get("country").get("phoneCountryCode").asString()).isEqualTo("+254");
		assertThat(summary.get("country").get("calendarCodes")).extracting(JsonNode::asString).containsExactly("GREGORIAN");
		assertThat(summary.get("country").get("languageCodes")).extracting(JsonNode::asString).contains("en", "sw");

		// staff added with a national number are read in the school's country, with no country field
		String national = "07" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
		JsonNode created = call(HttpMethod.POST, "/api/v1/schools/" + kenya + "/staff", admin,
				Map.of("phone", national, "givenName", "Wanjiru", "staffType", "TEACHER"), 201);
		assertThat(this.jdbc.queryForObject("select phone_e164 from users where id = ?", String.class,
				UUID.fromString(created.get("userId").asString()))).isEqualTo("+254" + national.substring(1));
	}

	@Test
	void aRosterImportIsReadInTheSchoolsCountryAndItsCalendars() throws Exception {
		UUID kenya = this.personas.newSchoolIn("KE", "Import school KE");
		Persona admin = this.personas.schoolAdmin(kenya, "import-admin-ke");
		String national = "07" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
		String csv = "given_name,father_name,phone,staff_type\nWanjiru,Kamau," + national + ",TEACHER\nOtieno,Odhiambo,0911000099,TEACHER\n";

		JsonNode job = validated(admin, kenya, "STAFF", uploadCsv(admin, kenya, csv), null);

		assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("VALIDATED");
		JsonNode first = rows(admin, kenya, id(job), null).get(0);
		assertThat(first.get("normalized").get("phone").asString()).isEqualTo("+254" + national.substring(1));

		// Ethiopian-calendar dates are refused for a country that does not use that calendar ...
		UUID media = uploadCsv(admin, kenya, "given_name,father_name,date_of_birth\nA,B,2005-01-06\n");
		JsonNode problem = call(HttpMethod.POST, imports(kenya), admin, Map.of("importType", "STUDENTS", "mediaId", media.toString(),
				"options", Map.of("dateSystem", "ETHIOPIAN")), 422);
		assertThat(problem.get("code").asString()).isEqualTo("import.date_system_not_available");
		// ... but accepted for a school whose country has it
		UUID ethiopia = this.personas.newSchool("Import school ET");
		Persona ethiopianAdmin = this.personas.schoolAdmin(ethiopia, "import-admin-et");
		JsonNode accepted = createImport(ethiopianAdmin, ethiopia, "STUDENTS", uploadCsv(ethiopianAdmin, ethiopia,
				"given_name,father_name,date_of_birth\nA,B,2005-01-06\n"), Map.of("dateSystem", "ETHIOPIAN"));
		assertThat(accepted.get("job").get("id").asString()).isNotBlank();
	}

	@Test
	void teachersAndPortfolioDatesOfASchoolUseTheSchoolsZone() {
		UUID kenya = this.personas.newSchoolIn("KE", "Zone school KE");
		// Nairobi is UTC+3 and Addis Ababa too, so use a school whose own zone differs from its country's default
		this.jdbc.update("update schools set timezone = 'Pacific/Auckland' where id = ?", kenya);
		assertThat(this.resolver.schoolZone(kenya)).isEqualTo(ZoneId.of("Pacific/Auckland"));
		this.jdbc.update("update schools set timezone = 'Not/AZone' where id = ?", kenya);
		assertThat(this.resolver.schoolZone(kenya)).as("an invalid school zone falls back to the country's default")
			.isEqualTo(ZoneId.of("Africa/Nairobi"));
	}

	// --- consent and policy text: never another country's -----------------------------------------

	@Test
	void legalTextOfAnotherCountryIsNeverServed() throws Exception {
		UUID kenya = this.personas.newSchoolIn("KE", "Policy school KE");

		// Ethiopia has its seeded text; Kenya has none: a clear refusal, not the Ethiopian text
		JsonNode et = call(HttpMethod.GET, "/api/v1/policies/TERMS?country=ET&lang=en", null, null, 200);
		assertThat(et.get("countryCode").asString()).isEqualTo("ET");
		for (String url : new String[] { "/api/v1/policies/TERMS?country=KE", "/api/v1/policies/MEDIA_PUBLICATION?schoolId=" + kenya }) {
			JsonNode refused = call(HttpMethod.GET, url, null, null, 404);
			assertThat(refused.get("code").asString()).as(url).isEqualTo("policy.country_not_available");
		}
		// without a school or a country the platform default decides (Ethiopia here), by data
		assertThat(call(HttpMethod.GET, "/api/v1/policies/PRIVACY", null, null, 200).get("countryCode").asString()).isEqualTo("ET");

		// a guardian of a Kenyan child cannot accept the Ethiopian text for the Kenyan school
		UUID student = this.personas.newStudent(kenya, "Kenyan child");
		UUID adult = this.personas.newUser("kenyan-guardian").userId();
		this.personas.linkGuardian(student, adult, "ACTIVE", false);
		UUID ethiopianVersion = this.jdbc.queryForObject("""
				select v.id from policy_versions v join policy_documents d on d.id = v.policy_document_id
				where d.policy_type = 'MEDIA_PUBLICATION' and d.country_code = 'ET' and d.school_id is null and v.language_code = 'en'
				  and v.version_label = '2026-11-draft'""", UUID.class);
		JsonNode decision = call(HttpMethod.POST, "/api/v1/me/consents", this.personas.personaFor("kenyan-guardian", adult),
				Map.of("studentId", student, "consentType", "MEDIA_PUBLICATION", "policyVersionId", ethiopianVersion,
						"decision", "GRANTED"),
				409);
		assertThat(decision.get("code").asString()).isEqualTo("policy.country_not_available");
		assertThat(anyConsentRecords(student)).isZero();

		// once Kenya has its own published text it is served, and only for Kenya
		UUID document = UUID.randomUUID();
		this.jdbc.update("insert into policy_documents (id, policy_type, country_code, title) values (?, 'MEDIA_PUBLICATION', 'KE', 'Photos')",
				document);
		this.jdbc.update("""
				insert into policy_versions (policy_document_id, version_label, language_code, content, effective_from, published_at)
				values (?, 'ke-1', 'en', 'Kenyan wording', now() - interval '1 day', now() - interval '1 day')""", document);
		JsonNode ke = call(HttpMethod.GET, "/api/v1/policies/MEDIA_PUBLICATION?schoolId=" + kenya, null, null, 200);
		assertThat(ke.get("countryCode").asString()).isEqualTo("KE");
		assertThat(ke.get("content").asString()).isEqualTo("Kenyan wording");
	}

	@Test
	void emergencyInformationIsAskedPerCountryAndNeverBorrowedFromAnother() throws Exception {
		Persona user = this.personas.plainUser();
		assertThat(call(HttpMethod.GET, "/api/v1/safety/emergency-info?country=ET", user, null, 200).get("country").asString())
			.isEqualTo("ET");
		JsonNode missing = call(HttpMethod.GET, "/api/v1/safety/emergency-info?country=KE", user, null, 404);
		assertThat(missing.get("code").asString()).isEqualTo("safety.emergency_info_not_found");
		call(HttpMethod.GET, "/api/v1/safety/emergency-info", user, null, 400);
	}

	private int anyConsentRecords(UUID student) {
		return this.jdbc.queryForObject("select count(*) from consent_records where subject_user_id = ?", Integer.class, student);
	}

}
