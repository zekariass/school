package com.nemaz.school.communications;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/** The recipient's inbox, the school's list, paging and who finds what. */
@IntegrationTest
class AnnouncementInboxTest extends CommunicationsTestSupport {

	private List<String> pageThrough(String url, Persona as, int limit) throws Exception {
		List<String> ids = new ArrayList<>();
		String cursor = null;
		for (int i = 0; i < 20; i++) {
			String separator = url.contains("?") ? "&" : "?";
			JsonNode page = get(url + separator + "limit=" + limit + ((cursor != null) ? "&cursor=" + cursor : ""), as);
			ids.addAll(idsOf(page));
			if (page.get("nextCursor") == null || page.get("nextCursor").isNull()) {
				return ids;
			}
			cursor = page.get("nextCursor").asString();
		}
		throw new AssertionError("too many pages");
	}

	@Test
	void theInboxIsNewestFirstOrUnreadFirstAndPagesWithoutRepeats() throws Exception {
		Comms c = comms("inbox-order");
		UUID one = publishNow(c, c.admin(), "One", classAudience(c.class1A()));
		UUID two = publishNow(c, c.admin(), "Two", classAudience(c.class1A()));
		UUID three = publishNow(c, c.admin(), "Three", classAudience(c.class1A()));
		UUID other = publishNow(c, c.admin(), "Other class", classAudience(c.class1B()));
		for (UUID id : List.of(one, two, three, other)) {
			awaitDelivered(id);
		}
		call(HttpMethod.POST, c.api() + "/" + two + "/read", c.gA1(), null, 204);
		String inbox = "/api/v1/me/announcements";
		assertThat(idsOf(get(inbox, c.gA1()))).containsExactly(three.toString(), two.toString(), one.toString());
		assertThat(idsOf(get(inbox + "?sort=UNREAD_FIRST", c.gA1()))).containsExactly(three.toString(), one.toString(), two.toString());
		assertThat(idsOf(get(inbox + "?filter=UNREAD", c.gA1()))).containsExactly(three.toString(), one.toString());
		// paging one at a time returns the same sequence, in both orders
		assertThat(pageThrough(inbox, c.gA1(), 1)).containsExactly(three.toString(), two.toString(), one.toString());
		assertThat(pageThrough(inbox + "?sort=UNREAD_FIRST", c.gA1(), 1)).containsExactly(three.toString(), one.toString(), two.toString());
		// the cards say what the caller read
		JsonNode cards = get(inbox + "?sort=UNREAD_FIRST", c.gA1());
		assertThat(absent(cards.get("items").get(0), "readAt")).isTrue();
		assertThat(absent(cards.get("items").get(2), "readAt")).isFalse();
		assertThat(cards.get("items").get(0).get("excerpt").asString()).isEqualTo("Body of Three");
		assertThat(cards.get("items").get(0).get("schoolName").asString()).isEqualTo("inbox-order");
		// each family sees its own class's announcements only
		assertThat(idsOf(get(inbox, c.gB1()))).containsExactly(other.toString());
		assertThat(idsOf(get(inbox, c.gX()))).isEmpty();
		assertThat(idsOf(get(inbox, c.lead1A()))).isEmpty();
		assertThat(idsOf(get(inbox, c.admin()))).isEmpty();
		// a link that is no longer valid takes the announcements with it
		assertThat(idsOf(get(inbox, c.gRevoked()))).isEmpty();
		assertThat(idsOf(get(inbox, c.gRestricted()))).isEmpty();
		call(HttpMethod.GET, inbox + "?cursor=garbage", c.gA1(), null, 400);
	}

	@Test
	void theAcknowledgementFilterAndTheBadgeAgree() throws Exception {
		Comms c = comms("inbox-ack");
		UUID asks = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Asks", "b", classAudience(c.class1A())),
				"mode", "PUBLISH", "type", "IMPORTANT", "requiresAcknowledgement", true), 201).get("id").asString());
		UUID plain = publishNow(c, c.admin(), "Plain", classAudience(c.class1A()));
		awaitDelivered(asks);
		awaitDelivered(plain);
		assertThat(idsOf(get("/api/v1/me/announcements?filter=NEEDS_ACKNOWLEDGEMENT", c.gA1()))).containsExactly(asks.toString());
		JsonNode badge = get("/api/v1/me/announcements/summary", c.gA1());
		assertThat(badge.get("unread").asInt()).isEqualTo(2);
		assertThat(badge.get("needingAcknowledgement").asInt()).isEqualTo(1);
		call(HttpMethod.POST, c.api() + "/" + asks + "/acknowledge", c.gA1(), null, 200);
		assertThat(idsOf(get("/api/v1/me/announcements?filter=NEEDS_ACKNOWLEDGEMENT", c.gA1()))).isEmpty();
		badge = get("/api/v1/me/announcements/summary", c.gA1());
		assertThat(badge.get("unread").asInt()).isEqualTo(1);
		assertThat(badge.get("needingAcknowledgement").asInt()).isZero();
		// the person with nothing has an empty badge
		badge = get("/api/v1/me/announcements/summary", c.gB1());
		assertThat(badge.get("unread").asInt()).isZero();
	}

	@Test
	void aGuardianWithChildrenInTwoSchoolsSeesBothAndEachSchoolOnlyItsOwn() throws Exception {
		Comms first = comms("inbox-two-a");
		Comms second = comms("inbox-two-b");
		Persona parent = this.personas.newUser("parent-of-two");
		this.personas.linkGuardian(first.a1(), parent.userId(), "ACTIVE", false);
		this.personas.linkGuardian(second.a2(), parent.userId(), "ACTIVE", false);
		UUID a = publishNow(first, first.admin(), "School A news", classAudience(first.class1A()));
		UUID b = publishNow(second, second.admin(), "School B news", classAudience(second.class1A()));
		awaitDelivered(a);
		awaitDelivered(b);
		assertThat(idsOf(get("/api/v1/me/announcements", parent))).containsExactly(b.toString(), a.toString());
		assertThat(idsOf(get("/api/v1/me/announcements?schoolId=" + first.schoolId(), parent))).containsExactly(a.toString());
		assertThat(idsOf(get(first.api(), parent))).containsExactly(a.toString());
		assertThat(idsOf(get(second.api(), parent))).containsExactly(b.toString());
		// an announcement is only ever found under its own school
		call(HttpMethod.GET, second.api() + "/" + a, parent, null, 404);
		call(HttpMethod.POST, second.api() + "/" + a + "/read", parent, null, 404);
		// a guardian of one school's child does not see the other school at all
		call(HttpMethod.GET, second.api(), first.gA1(), null, 404);
	}

	@Test
	void theSchoolListShowsTheManagersEverythingAndTheAuthorTheirOwn() throws Exception {
		Comms c = comms("inbox-school-list");
		UUID byTeacher = publishNow(c, c.lead1A(), "By teacher", classAudience(c.class1A()));
		UUID byAdmin = publishNow(c, c.admin(), "By admin", classAudience(c.class2A()));
		awaitDelivered(byTeacher);
		awaitDelivered(byAdmin);
		// a manager sees every published announcement; the teacher sees their own, not the other class's
		assertThat(idsOf(get(c.api(), c.admin()))).containsExactly(byAdmin.toString(), byTeacher.toString());
		assertThat(idsOf(get(c.api(), c.lead1A()))).containsExactly(byTeacher.toString());
		assertThat(idsOf(get(c.api(), c.lead1B()))).isEmpty();
		assertThat(idsOf(get(c.api(), c.gA1()))).containsExactly(byTeacher.toString());
		assertThat(idsOf(get(c.api(), c.gX()))).containsExactly(byAdmin.toString());
		// the authored list: a teacher's own, the manager's all; mine=true narrows the manager
		assertThat(idsOf(get(c.api() + "/authored", c.lead1A()))).containsExactly(byTeacher.toString());
		assertThat(idsOf(get(c.api() + "/authored", c.admin()))).containsExactly(byAdmin.toString(), byTeacher.toString());
		assertThat(idsOf(get(c.api() + "/authored?mine=true", c.admin()))).containsExactly(byAdmin.toString());
		assertThat(idsOf(get(c.api() + "/authored?status=DRAFT", c.admin()))).isEmpty();
		JsonNode card = get(c.api() + "/authored", c.lead1A()).get("items").get(0);
		assertThat(card.get("recipientCount").asInt()).isEqualTo(2);
		assertThat(absent(get(c.api(), c.gA1()).get("items").get(0), "recipientCount")).isTrue();
		// nobody outside the school finds any of it
		Comms other = comms("inbox-school-list-other");
		call(HttpMethod.GET, c.api(), other.admin(), null, 404);
		call(HttpMethod.GET, c.api() + "/authored", other.admin(), null, 404);
		call(HttpMethod.GET, c.api(), this.personas.newUser("nobody"), null, 404);
	}

}
