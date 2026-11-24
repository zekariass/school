package com.nemaz.school.notifications;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.support.Persona;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class InboxApiTest extends NotificationTestSupport {

	private List<UUID> create(Persona user, int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(this.notifications.notify(NotificationRequest.to("NEW_POST", user.userId()).args("5A", "Teacher " + i)
				.path("/posts/" + i).build()).getFirst());
		}
		return ids;
	}

	private JsonNode body(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	@Test
	void pagesNewestFirstWithoutGapsOrRepeats() throws Exception {
		Persona user = this.personas.newUser("inbox-pages");
		create(user, 25);
		List<String> expected = this.jdbc.queryForList(
				"select id::text from notifications where user_id = ? order by created_at desc, id desc", String.class,
				user.userId());

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = body(this.mvc.perform(get("/api/v1/notifications").param("limit", "10")
				.param("cursor", (cursor != null) ? cursor : "")
				.with(user.bearer())).andExpect(status().isOk()));
			page.get("items").forEach((n) -> seen.add(n.get("id").asString()));
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
			pages++;
			if (cursor == null) {
				break;
			}
		}
		while (pages < 10);

		assertThat(pages).isEqualTo(3);
		assertThat(seen).containsExactlyElementsOf(expected);
	}

	@Test
	void anItemCarriesWhatTheClientNeeds() throws Exception {
		Persona user = this.personas.newUser("inbox-shape");
		create(user, 1);
		this.mvc.perform(get("/api/v1/notifications").with(user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].typeCode").value("NEW_POST"))
			.andExpect(jsonPath("$.items[0].title").value("New post in 5A"))
			.andExpect(jsonPath("$.items[0].body").value("Teacher 0 shared an update."))
			.andExpect(jsonPath("$.items[0].data.path").value("/posts/0"))
			.andExpect(jsonPath("$.items[0].priority").value("NORMAL"))
			.andExpect(jsonPath("$.items[0].readAt").doesNotExist())
			.andExpect(jsonPath("$.items[0].createdAt").exists())
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void unreadCountReadAndReadAll() throws Exception {
		Persona user = this.personas.newUser("inbox-read");
		List<UUID> ids = create(user, 4);

		this.mvc.perform(get("/api/v1/notifications/unread-count").with(user.bearer()))
			.andExpect(jsonPath("$.count").value(4));

		this.mvc.perform(post("/api/v1/notifications/" + ids.getFirst() + "/read").with(user.bearer()))
			.andExpect(status().isNoContent());
		// idempotent
		this.mvc.perform(post("/api/v1/notifications/" + ids.getFirst() + "/read").with(user.bearer()))
			.andExpect(status().isNoContent());
		this.mvc.perform(get("/api/v1/notifications/unread-count").with(user.bearer()))
			.andExpect(jsonPath("$.count").value(3));
		this.mvc.perform(get("/api/v1/notifications").param("unreadOnly", "true").with(user.bearer()))
			.andExpect(jsonPath("$.items.length()").value(3));

		this.mvc.perform(post("/api/v1/notifications/read-all").with(user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updated").value(3));
		this.mvc.perform(get("/api/v1/notifications/unread-count").with(user.bearer()))
			.andExpect(jsonPath("$.count").value(0));
		this.mvc.perform(get("/api/v1/notifications").with(user.bearer()))
			.andExpect(jsonPath("$.items.length()").value(4));
		// the first read time is kept
		assertThat(this.jdbc.queryForObject("select count(distinct read_at) from notifications where user_id = ?",
				Integer.class, user.userId())).isEqualTo(2);
	}

	@Test
	void archivedItemsLeaveTheInboxAndTheBadge() throws Exception {
		Persona user = this.personas.newUser("inbox-archive");
		List<UUID> ids = create(user, 3);

		this.mvc.perform(post("/api/v1/notifications/" + ids.get(1) + "/archive").with(user.bearer()))
			.andExpect(status().isNoContent());

		this.mvc.perform(get("/api/v1/notifications").with(user.bearer()))
			.andExpect(jsonPath("$.items.length()").value(2));
		this.mvc.perform(get("/api/v1/notifications/unread-count").with(user.bearer()))
			.andExpect(jsonPath("$.count").value(2));
		this.mvc.perform(post("/api/v1/notifications/" + ids.get(1) + "/archive").with(user.bearer()))
			.andExpect(status().isNoContent());
	}

	@Test
	void anotherUsersNotificationIsNotFound() throws Exception {
		Persona owner = this.personas.newUser("inbox-owner");
		Persona other = this.personas.newUser("inbox-other");
		UUID id = create(owner, 1).getFirst();

		this.mvc.perform(post("/api/v1/notifications/" + id + "/read").with(other.bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("notification.not_found"));
		this.mvc.perform(post("/api/v1/notifications/" + id + "/archive").with(other.bearer()))
			.andExpect(status().isNotFound());
		this.mvc.perform(post("/api/v1/notifications/" + UUID.randomUUID() + "/read").with(other.bearer()))
			.andExpect(status().isNotFound());
		// and it stays untouched
		assertThat(this.jdbc.queryForObject("select read_at is null and archived_at is null from notifications where id = ?",
				Boolean.class, id)).isTrue();
		this.mvc.perform(get("/api/v1/notifications").with(other.bearer())).andExpect(jsonPath("$.items.length()").value(0));
		this.mvc.perform(post("/api/v1/notifications/read-all").with(other.bearer()))
			.andExpect(jsonPath("$.updated").value(0));
		assertThat(this.jdbc.queryForObject("select read_at is null from notifications where id = ?", Boolean.class, id))
			.isTrue();
	}

	@Test
	void aMalformedCursorIsRejected() throws Exception {
		Persona user = this.personas.newUser("inbox-cursor");
		this.mvc.perform(get("/api/v1/notifications").param("cursor", "not-a-cursor").with(user.bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_cursor"));
	}

	@Test
	void theTestEndpointNotifiesTheCallerAndIsForPlatformAdminsOnly() throws Exception {
		Persona admin = this.personas.platformAdmin();
		int before = this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = 'TEST_NOTIFICATION'",
				Integer.class, admin.userId());
		String id = body(this.mvc.perform(post("/api/v1/notifications/test").with(admin.bearer()))
			.andExpect(status().isOk())).get("notificationId").asString();
		assertThat(this.jdbc.queryForObject("select count(*) from notifications where user_id = ? and type_code = 'TEST_NOTIFICATION'",
				Integer.class, admin.userId())).isEqualTo(before + 1);
		assertThat(this.jdbc.queryForObject("select priority from notifications where id = ?::uuid", String.class, id))
			.isEqualTo("URGENT");

		this.mvc.perform(post("/api/v1/notifications/test").with(this.personas.plainUser().bearer()))
			.andExpect(status().isForbidden());
	}

}
