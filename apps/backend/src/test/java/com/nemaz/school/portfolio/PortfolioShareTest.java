package com.nemaz.school.portfolio;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule book P6: sharing to the class story is deferred; the endpoint exists and says so, to the right people only. */
@IntegrationTest
class PortfolioShareTest extends PortfolioTestSupport {

	private static String share(Pf p, UUID item) {
		return p.item(item) + "/share-to-story";
	}

	private int posts(Pf p) {
		return this.jdbc.queryForObject("select count(*) from posts where school_id = ?", Integer.class, p.schoolId());
	}

	@Test
	void shareToStoryIsUnsupportedForTeachers() throws Exception {
		Pf p = pf("sh1");
		cap(p, "CLASS");
		UUID id = approved(p, p.student1(), "for the story", "CLASS");
		for (Persona teacher : List.of(p.lead(), p.co(), p.admin())) {
			assertThat(code(post(share(p, id), teacher, null, 422))).as(teacher.name()).isEqualTo("portfolio.share_unsupported");
		}
		// switching the reserved setting on changes nothing: nothing is built behind it
		setting(p.schoolId(), "portfolio.share_to_story.enabled", true);
		assertThat(code(post(share(p, id), p.lead(), null, 422))).isEqualTo("portfolio.share_unsupported");
		assertThat(posts(p)).isZero();
		assertThat(get(p.api() + "/portfolio/capabilities", p.lead()).get("shareToStoryAvailable").asBoolean()).isFalse();
		assertThat(get(p.item(id), p.lead()).get("capabilities").get("canShareToStory").asBoolean()).isFalse();
	}

	@Test
	void studentsAndOtherPeopleCannotEvenTry() throws Exception {
		Pf p = pf("sh2");
		cap(p, "CLASS");
		UUID id = approved(p, p.student1(), "no story", "CLASS");
		for (Persona who : List.of(p.student1(), p.student2(), p.guardian1(), p.assistant())) {
			assertThat(code(post(share(p, id), who, null, 403))).as(who.name()).isEqualTo("portfolio.review_not_allowed");
		}
		for (Persona who : List.of(p.otherTeacher(), p.otherStudent(), p.guardian2(), p.restricted(), p.adminB(), p.studentB())) {
			assertThat(statusOf(HttpMethod.POST, share(p, id), who, null)).as(who.name()).isEqualTo(404);
		}
		assertThat(statusOf(HttpMethod.POST, share(p, id), null, null)).isEqualTo(401);
		assertThat(posts(p)).isZero();
	}

	@Test
	void shareRequiresAnApprovedClassVisibleItem() throws Exception {
		Pf p = pf("sh3");
		UUID submitted = submitted(p, p.student1(), "not approved");
		assertThat(code(post(share(p, submitted), p.lead(), null, 409))).isEqualTo("portfolio.share_not_eligible");
		UUID familyOnly = approved(p, p.student1(), "family only", "GUARDIANS");
		assertThat(code(post(share(p, familyOnly), p.lead(), null, 409))).isEqualTo("portfolio.share_not_eligible");
		// a CLASS item under a cap that lowers it to GUARDIANS is family only too
		UUID classItem = approved(p, p.student1(), "class level", "CLASS");
		assertThat(code(post(share(p, classItem), p.lead(), null, 409))).isEqualTo("portfolio.share_not_eligible");
		cap(p, "CLASS");
		assertThat(code(post(share(p, classItem), p.lead(), null, 422))).isEqualTo("portfolio.share_unsupported");
	}

}
