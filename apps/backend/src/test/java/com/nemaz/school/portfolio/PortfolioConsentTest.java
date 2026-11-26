package com.nemaz.school.portfolio;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P4 (consent): the gate at approval, withholding on every read, the confirmation, tagged children. */
@IntegrationTest
class PortfolioConsentTest extends PortfolioTestSupport {

	private static List<String> studentIds(JsonNode problem) {
		List<String> ids = new ArrayList<>();
		problem.get("students").forEach((s) -> ids.add(s.get("studentId").asString()));
		return ids;
	}

	@Test
	void classAndSchoolMediaNeedConsentAtApproval() throws Exception {
		Pf p = pf("con1");
		cap(p, "SCHOOL");
		for (String audience : List.of("CLASS", "SCHOOL")) {
			UUID media = photo(p.student1(), p.schoolId());
			UUID id = id(create(p.student1(), p, photoBody("for " + audience, List.of(media), "mode", "SUBMIT", "visibility", audience,
					"confirmNoUnconsentedStudents", true), 201));
			JsonNode refused = post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 422);
			assertThat(code(refused)).isEqualTo("portfolio.media_consent_missing");
			assertThat(studentIds(refused)).containsExactly(p.s1().toString());
			assertThat(refused.get("students").get(0).get("displayName").asString()).isEqualTo("Abel");
			assertThat(status(id)).isEqualTo("SUBMITTED");
			// the same refusal for an administrator who is not on the class roster: ids only for people who may not see it
			JsonNode viaAdmin = post(p.item(id) + "/review", p.admin(), body("decision", "APPROVE"), 422);
			assertThat(code(viaAdmin)).isEqualTo("portfolio.media_consent_missing");
			grant(p.guardian1(), p.s1());
			post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
			withdraw(p.guardian1(), p.s1());
		}
	}

	@Test
	void ownGuardiansNeedNoConsent() throws Exception {
		Pf p = pf("con2");
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("family only", List.of(media), "mode", "SUBMIT", "visibility", "GUARDIANS"), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		JsonNode seen = get(p.item(id), p.guardian1());
		assertThat(seen.get("media").size()).isEqualTo(1);
		assertThat(seen.get("mediaWithheld").asBoolean()).isFalse();
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
		// and the school's cap makes a CLASS item guardian-only without needing consent either
		UUID second = photo(p.student1(), p.schoolId());
		UUID wide = id(create(p.student1(), p, photoBody("wide", List.of(second), "mode", "SUBMIT", "visibility", "SCHOOL"), 201));
		post(p.item(wide) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(get(p.item(wide), p.guardian1()).get("effectiveVisibility").asString()).isEqualTo("GUARDIANS");
		assertThat(mediaUrlStatus(p.guardian1(), second)).isEqualTo(200);
		assertThat(statusOf(HttpMethod.GET, p.item(wide), p.student2(), null)).isEqualTo(404);
	}

	@Test
	void withdrawalWithholdsFromClassmatesButNotFromStudentReviewersOrOwnGuardians() throws Exception {
		Pf p = pf("con3");
		cap(p, "CLASS");
		grant(p.guardian1(), p.s1());
		UUID id = approvedPhoto(p, p.student1(), "class photo", "CLASS");
		UUID media = mediaOf(get(p.item(id), p.student1()));
		for (Who who : people(p)) {
			assertThat(mediaUrlStatus(who.persona(), media)).as(who.label() + " before").isEqualTo(200);
		}
		assertThat(get(p.item(id), p.student2()).get("media").size()).isEqualTo(1);
		withdraw(p.guardian1(), p.s1());
		// classmates and other class members: the item stays, the files are gone
		for (String who : List.of("classmate", "assistant")) {
			Persona persona = who.equals("classmate") ? p.student2() : p.assistant();
			JsonNode view = get(p.item(id), persona);
			assertThat(view.get("media").size()).as(who).isZero();
			assertThat(view.get("mediaWithheld").asBoolean()).as(who).isTrue();
			assertThat(view.get("title").asString()).isEqualTo("class photo");
			assertThat(mediaUrlStatus(persona, media)).as(who).isEqualTo(404);
		}
		assertThat(ids(get(p.classApi() + "/portfolio/gallery", p.student2()))).containsExactly(id.toString());
		// the student, the reviewers and the student's own guardians keep the files
		for (Persona who : List.of(p.student1(), p.lead(), p.admin(), p.guardian1())) {
			JsonNode view = get(p.item(id), who);
			assertThat(view.get("media").size()).as(who.name()).isEqualTo(1);
			assertThat(mediaUrlStatus(who, media)).as(who.name()).isEqualTo(200);
		}
		// reviewers and the student are warned; the guardian, who sees everything, is not
		assertThat(get(p.item(id), p.lead()).get("mediaWithheld").asBoolean()).isTrue();
		assertThat(get(p.item(id), p.student1()).get("mediaWithheld").asBoolean()).isTrue();
		assertThat(get(p.item(id), p.guardian1()).get("mediaWithheld").asBoolean()).isFalse();
	}

	private record Who(String label, Persona persona) {
	}

	private static List<Who> people(Pf p) {
		return List.of(new Who("classmate", p.student2()), new Who("guardian", p.guardian1()), new Who("lead", p.lead()),
				new Who("student", p.student1()));
	}

	@Test
	void grantingConsentAgainRestoresTheMedia() throws Exception {
		Pf p = pf("con4");
		cap(p, "CLASS");
		grant(p.guardian1(), p.s1());
		UUID id = approvedPhoto(p, p.student1(), "back again", "CLASS");
		UUID media = mediaOf(get(p.item(id), p.student1()));
		withdraw(p.guardian1(), p.s1());
		assertThat(mediaUrlStatus(p.student2(), media)).isEqualTo(404);
		grant(p.guardian1(), p.s1());
		assertThat(mediaUrlStatus(p.student2(), media)).isEqualTo(200);
		assertThat(get(p.item(id), p.student2()).get("media").size()).isEqualTo(1);
		assertThat(get(p.item(id), p.student2()).get("mediaWithheld").asBoolean()).isFalse();
		// a guardian link that ends takes the consent it carried with it: the files are withheld at once, without any event
		this.jdbc.update("update student_guardians set status = 'REVOKED', revoked_at = now() where guardian_id = ? and student_id = ?",
				p.guardian1().userId(), p.s1());
		assertThat(mediaUrlStatus(p.student2(), media)).isEqualTo(404);
	}

	@Test
	void aTaggedChildWithoutConsentWithholdsEvenFromOwnGuardians() throws Exception {
		Pf p = pf("con5");
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("group", List.of(media), "mode", "SUBMIT", "visibility", "GUARDIANS",
				"taggedStudentIds", List.of(p.s2())), 201));
		assertThat(get(p.item(id), p.student1()).get("showsOtherChildren").asBoolean()).isTrue();
		// at approval: the student and the tagged child both need consent; only the missing ones are listed
		JsonNode refused = post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 422);
		assertThat(studentIds(refused)).containsExactlyInAnyOrder(p.s1().toString(), p.s2().toString());
		grant(p.guardian1(), p.s1());
		assertThat(studentIds(post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 422))).containsExactly(p.s2().toString());
		grant(p.guardian2(), p.s2());
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
		// the tagged child's consent is withdrawn: the student's own guardian no longer sees a child whose guardians did not agree
		withdraw(p.guardian2(), p.s2());
		JsonNode guardianView = get(p.item(id), p.guardian1());
		assertThat(guardianView.get("media").size()).isZero();
		assertThat(guardianView.get("mediaWithheld").asBoolean()).isTrue();
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(200);
		assertThat(mediaUrlStatus(p.lead(), media)).isEqualTo(200);
		// the student's own consent missing is a different thing: the own guardian keeps seeing their child's work
		grant(p.guardian2(), p.s2());
		withdraw(p.guardian1(), p.s1());
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
		// the tagged children are named to the student and reviewers only
		assertThat(get(p.item(id), p.guardian1()).get("taggedStudents")).isNull();
		assertThat(get(p.item(id), p.guardian1()).get("taggedStudentCount").asInt()).isEqualTo(1);
		assertThat(get(p.item(id), p.lead()).get("taggedStudents").get(0).get("displayName").asString()).isEqualTo("Bethlehem");
	}

	@Test
	void confirmationIsRequiredWithoutTagsAndIsAudited() throws Exception {
		Pf p = pf("con6");
		cap(p, "CLASS");
		grant(p.guardian1(), p.s1());
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("unconfirmed", List.of(media), "mode", "SUBMIT", "visibility", "CLASS"), 201));
		assertThat(code(post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 422))).isEqualTo("portfolio.media_confirmation_required");
		// a photo that may show others needs it even for the family
		UUID second = photo(p.student1(), p.schoolId());
		UUID others = id(create(p.student1(), p, photoBody("others", List.of(second), "mode", "SUBMIT", "visibility", "GUARDIANS",
				"showsOtherChildren", true), 201));
		assertThat(code(post(p.item(others) + "/review", p.lead(), body("decision", "APPROVE"), 422))).isEqualTo("portfolio.media_confirmation_required");
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'portfolio.item.media_confirmed' and target_id = ?",
				Integer.class, id)).isZero();
		// the creator confirms (here as a student, while the item is still a draft) and the item can be approved
		UUID third = photo(p.student1(), p.schoolId());
		UUID draft = id(create(p.student1(), p, photoBody("confirm later", List.of(third), "visibility", "CLASS"), 201));
		JsonNode confirmed = call(HttpMethod.PATCH, p.item(draft), p.student1(), body("confirmNoUnconsentedStudents", true), 200);
		assertThat(confirmed.get("mediaConfirmed").asBoolean()).isTrue();
		post(p.item(draft) + "/submit", p.student1(), null, 200);
		post(p.item(draft) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_logs where action = 'portfolio.item.media_confirmed' and target_id = ?",
				Integer.class, draft)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select media_confirmed_by from portfolio_items where id = ?", UUID.class, draft)).isEqualTo(p.s1());
	}

	@Test
	void confirmationIsDroppedWhenFilesOrTagsChange() throws Exception {
		Pf p = pf("con7");
		UUID first = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("conf", List.of(first), "confirmNoUnconsentedStudents", true), 201));
		assertThat(get(p.item(id), p.student1()).get("mediaConfirmed").asBoolean()).isTrue();
		// text changes keep it
		assertThat(call(HttpMethod.PATCH, p.item(id), p.student1(), body("title", "still confirmed"), 200).get("mediaConfirmed").asBoolean()).isTrue();
		// other files drop it
		UUID second = photo(p.student1(), p.schoolId());
		assertThat(call(HttpMethod.PATCH, p.item(id), p.student1(), body("mediaIds", List.of(first, second)), 200).get("mediaConfirmed").asBoolean()).isFalse();
		call(HttpMethod.PATCH, p.item(id), p.student1(), body("confirmNoUnconsentedStudents", true), 200);
		assertThat(get(p.item(id), p.student1()).get("mediaConfirmed").asBoolean()).isTrue();
		// removing a file drops it as well, and the discarded file is gone
		JsonNode removed = call(HttpMethod.PATCH, p.item(id), p.student1(), body("mediaIds", List.of(first)), 200);
		assertThat(removed.get("mediaConfirmed").asBoolean()).isFalse();
		assertThat(this.jdbc.queryForObject("select status from media where id = ?", String.class, second)).isEqualTo("DELETED");
		// tags drop it
		call(HttpMethod.PATCH, p.item(id), p.student1(), body("confirmNoUnconsentedStudents", true), 200);
		JsonNode tagged = call(HttpMethod.PATCH, p.item(id), p.student1(), body("taggedStudentIds", List.of(p.s2())), 200);
		assertThat(tagged.get("mediaConfirmed").asBoolean()).isFalse();
		assertThat(tagged.get("showsOtherChildren").asBoolean()).isTrue();
		assertThat(tagged.get("taggedStudentCount").asInt()).isEqualTo(1);
	}

	@Test
	void raisingTheCapNeverExposesUnconsentedMedia() throws Exception {
		Pf p = pf("con8");
		// approved while the cap was GUARDIANS: no gate ran, the family sees the picture
		UUID media = photo(p.student1(), p.schoolId());
		UUID id = id(create(p.student1(), p, photoBody("later raised", List.of(media), "mode", "SUBMIT", "visibility", "SCHOOL"), 201));
		post(p.item(id) + "/review", p.lead(), body("decision", "APPROVE"), 200);
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
		cap(p, "SCHOOL");
		// now the item reaches the school, but nobody has consented or confirmed: the files are withheld from everybody but the student and reviewers
		assertThat(get(p.item(id), p.otherStudent()).get("media").size()).isZero();
		assertThat(get(p.item(id), p.otherStudent()).get("mediaWithheld").asBoolean()).isTrue();
		assertThat(mediaUrlStatus(p.otherStudent(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(p.student2(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(404);
		assertThat(mediaUrlStatus(p.student1(), media)).isEqualTo(200);
		assertThat(mediaUrlStatus(p.lead(), media)).isEqualTo(200);
		cap(p, "GUARDIANS");
		assertThat(mediaUrlStatus(p.guardian1(), media)).isEqualTo(200);
	}

	@Test
	void textOnlyItemsNeedNoConsent() throws Exception {
		Pf p = pf("con9");
		cap(p, "SCHOOL");
		UUID id = approved(p, p.student1(), "just words", "SCHOOL");
		for (Who who : List.of(new Who("classmate", p.student2()), new Who("other", p.otherStudent()))) {
			JsonNode view = get(p.item(id), who.persona());
			assertThat(view.get("title").asString()).isEqualTo("just words");
			assertThat(view.get("mediaWithheld").asBoolean()).isFalse();
		}
	}

	@Test
	void taggedStudentsMustBeActiveInTheSchool() throws Exception {
		Pf p = pf("con10");
		UUID media = photo(p.student1(), p.schoolId());
		for (UUID bad : List.of(newId(), this.jdbc.queryForObject("select student_id from school_students where school_id = ?", UUID.class, p.schoolB()),
				this.jdbc.queryForObject("select student_id from school_students where status = 'WITHDRAWN' and school_id = ?", UUID.class, p.schoolId()))) {
			assertThat(code(create(p.student1(), p, photoBody("bad tag", List.of(media), "taggedStudentIds", List.of(bad)), 400))).isEqualTo("portfolio.tag_invalid");
		}
		List<UUID> many = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			many.add(newId());
		}
		assertThat(code(create(p.student1(), p, photoBody("many", List.of(media), "taggedStudentIds", many), 400))).isEqualTo("portfolio.too_many_tags");
		// the student themself in the list is ignored
		JsonNode ok = create(p.student1(), p, photoBody("self", List.of(media), "taggedStudentIds", List.of(p.s1())), 201);
		assertThat(ok.get("taggedStudentCount").asInt()).isZero();
	}

}
