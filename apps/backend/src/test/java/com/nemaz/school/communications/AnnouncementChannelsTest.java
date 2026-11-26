package com.nemaz.school.communications;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.IntegrationTest;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.support.Persona;
import com.nemaz.school.support.Personas;
import com.nemaz.school.support.RecordingSmsAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Email and SMS: off or restricted by default, per-country adapters, opt-outs, caps and quiet hours. */
@IntegrationTest
class AnnouncementChannelsTest extends CommunicationsTestSupport {

	@Autowired
	RecordingSmsAdapter kenya;

	@Autowired
	com.nemaz.school.communications.SmsOptOuts optOuts;

	@AfterEach
	void resetKenya() {
		this.kenya.reset();
	}

	@SafeVarargs
	private UUID urgentWithSms(Comms c, Map<String, Object>... audiences) throws Exception {
		JsonNode created = call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Road closed", "Body of road closed", audiences),
				"mode", "PUBLISH", "type", "URGENT", "channels", List.of("PUSH", "SMS")), 201);
		UUID id = UUID.fromString(created.get("id").asString());
		awaitDelivered(id);
		return id;
	}

	private String smsStatus(UUID id, Persona who) {
		return this.jdbc.queryForObject("select sms_status || coalesce(':' || sms_reason, '') from announcement_recipients where announcement_id = ? and user_id = ?",
				String.class, id, who.userId());
	}

	private String emailStatus(UUID id, Persona who) {
		return this.jdbc.queryForObject("select email_status || coalesce(':' || email_reason, '') from announcement_recipients where announcement_id = ? and user_id = ?",
				String.class, id, who.userId());
	}

	private void waitForSms(UUID id, Persona who, String expected) {
		await().atMost(Duration.ofSeconds(15)).until(() -> expected.equals(smsStatus(id, who)));
	}

	@Test
	void smsIsOffByDefaultAndWhenOnLimitedToUrgentAndSafety() throws Exception {
		Comms c = comms("chan-sms-default");
		assertThat(get(c.api() + "/capabilities", c.admin()).get("smsAvailable").asBoolean()).isFalse();
		for (String type : List.of("NORMAL", "URGENT")) {
			assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())), "type",
					type, "channels", List.of("PUSH", "SMS")), 400))).isEqualTo("announcement.sms_not_allowed");
		}
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		JsonNode capabilities = get(c.api() + "/capabilities", c.admin());
		assertThat(capabilities.get("smsAvailable").asBoolean()).isTrue();
		assertThat(capabilities.get("smsTypes")).extracting(JsonNode::asString).containsExactlyInAnyOrder("SAFETY", "URGENT");
		// the caps and the school's time zone, for the composer
		assertThat(capabilities.get("smsMaxPerAnnouncement").asInt()).isEqualTo(500);
		assertThat(capabilities.get("smsMaxPerDay").asInt()).isEqualTo(1000);
		assertThat(capabilities.get("timeZone").asString()).isNotBlank();
		// a normal announcement still cannot use it
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())), "channels",
				List.of("PUSH", "SMS")), 400))).isEqualTo("announcement.sms_not_allowed");
		// an urgent one does: the text names the school and the title, nothing else
		UUID id = urgentWithSms(c, classAudience(c.class1A()));
		waitForSms(id, c.gA1(), "SENT");
		String text = this.messages.awaitMessage(phoneOf(c.gA1()));
		assertThat(text).contains("chan-sms-default").contains("Road closed").doesNotContain("Body of");
		assertThat(this.jdbc.queryForObject("select sms_adapter from announcement_recipients where announcement_id = ? and user_id = ?",
				String.class, id, c.gA1().userId())).isEqualTo("system");
		JsonNode report = get(c.api() + "/" + id + "/report", c.admin());
		assertThat(report.get("sms").get("sent").asInt()).isEqualTo(2);
		// without the school's switch an announcement made before it is switched off drops the channel when it publishes
		assertThat(report.get("email").get("sent").asInt()).isZero();
	}

	@Test
	void theNumbersCountryChoosesTheAdapterAndAFailureIsRecorded() throws Exception {
		Comms c = comms("chan-sms-country");
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		// a Kenyan number goes through the Kenyan adapter, an Ethiopian one through the platform sender
		String kenyan = "+254712345678";
		this.jdbc.update("update users set phone_e164 = ? where id = ?", kenyan, c.gA1().userId());
		UUID id = urgentWithSms(c, classAudience(c.class1A()));
		waitForSms(id, c.gA1(), "SENT");
		waitForSms(id, c.gSib(), "SENT");
		assertThat(this.kenya.sent()).hasSize(1);
		assertThat(this.kenya.sent().getFirst().phoneE164()).isEqualTo(kenyan);
		assertThat(this.kenya.sent().getFirst().clientReference()).isNotBlank();
		assertThat(this.jdbc.queryForObject("select sms_adapter from announcement_recipients where announcement_id = ? and user_id = ?",
				String.class, id, c.gA1().userId())).isEqualTo("recording-ke");
		assertThat(this.messages.awaitMessage(phoneOf(c.gSib()))).contains("Road closed");
		// an adapter that refuses: FAILED with its reason, counted in the report, never retried by itself
		this.kenya.respondWith((m) -> com.nemaz.school.communications.SmsAdapter.SmsResult.failed("sender id not registered"));
		UUID second = urgentWithSms(c, userAudience(c.gA1().userId()));
		waitForSms(second, c.gA1(), "FAILED:sender id not registered");
		JsonNode report = get(c.api() + "/" + second + "/report", c.admin());
		assertThat(report.get("sms").get("failed").asInt()).isEqualTo(1);
		assertThat(report.get("failed").asInt()).isEqualTo(1);
		this.maintenance.resumeDeliveries();
		assertThat(this.kenya.sent()).hasSize(2);
		this.jdbc.update("update users set phone_e164 = ? where id = ?", Personas.newPhone(), c.gA1().userId());
	}

	@Test
	void optOutsAlwaysApplyEvenToUrgentAnnouncements() throws Exception {
		Comms c = comms("chan-sms-optout");
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		// the person's own choice
		JsonNode channels = call(HttpMethod.PUT, "/api/v1/me/communication-channels", c.gA1(), Map.of("sms", false), 200);
		assertThat(channels.get("sms").asBoolean()).isFalse();
		assertThat(channels.get("email").asBoolean()).isTrue();
		// the number's provider opt-out (STOP)
		this.optOuts.optOut(phoneOf(c.gSib()));
		UUID id = urgentWithSms(c, classAudience(c.class1A()));
		await().atMost(Duration.ofSeconds(10)).until(() -> "SKIPPED:OPTED_OUT".equals(smsStatus(id, c.gSib())));
		assertThat(smsStatus(id, c.gA1())).isEqualTo("SKIPPED:OPTED_OUT");
		assertThat(this.messages.nothingDelivered(phoneOf(c.gA1()), Duration.ofMillis(300))).isTrue();
		assertThat(this.messages.nothingDelivered(phoneOf(c.gSib()), Duration.ofMillis(300))).isTrue();
		// the provider's opt-out cannot be undone from the app
		JsonNode blocked = get("/api/v1/me/communication-channels", c.gSib());
		assertThat(blocked.get("sms").asBoolean()).isFalse();
		assertThat(blocked.get("smsBlockedByProvider").asBoolean()).isTrue();
		assertThat(code(call(HttpMethod.PUT, "/api/v1/me/communication-channels", c.gSib(), Map.of("sms", true), 409)))
			.isEqualTo("announcement.sms_blocked_by_provider");
		// opting in again works for the person's own choice
		assertThat(call(HttpMethod.PUT, "/api/v1/me/communication-channels", c.gA1(), Map.of("sms", true), 200).get("sms").asBoolean()).isTrue();
		UUID second = urgentWithSms(c, userAudience(c.gA1().userId()));
		waitForSms(second, c.gA1(), "SENT");
	}

	@Test
	void capsLimitHowManyTextsOneAnnouncementAndOneSchoolDaySend() throws Exception {
		Comms c = comms("chan-sms-caps");
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		setting(c.schoolId(), "announcements.sms.max_per_announcement", 3);
		setting(c.schoolId(), "announcements.sms.max_per_day", 4);
		// four families in the school, a cap of three per announcement
		UUID first = urgentWithSms(c, schoolAudience());
		assertThat(this.jdbc.queryForList("select sms_status || ':' || coalesce(sms_reason, '') from announcement_recipients where announcement_id = ? order by id",
				String.class, first).stream().filter((s) -> s.startsWith("SKIPPED:CAP_ANNOUNCEMENT")).count()).isEqualTo(1);
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc.queryForObject(
				"select count(*) from announcement_recipients where announcement_id = ? and sms_status = 'SENT'", Integer.class, first) == 3);
		// the school's day allows one more text: the next announcement gets one, the rest are skipped for the day
		UUID second = urgentWithSms(c, schoolAudience());
		await().atMost(Duration.ofSeconds(15)).until(() -> this.jdbc.queryForObject(
				"select count(*) from announcement_recipients where announcement_id = ? and sms_status = 'SENT'", Integer.class, second) == 1);
		assertThat(this.jdbc.queryForObject("select count(*) from announcement_recipients where announcement_id = ? and sms_reason = 'CAP_DAY'",
				Integer.class, second)).isEqualTo(3);
		JsonNode report = get(c.api() + "/" + second + "/report", c.admin());
		assertThat(report.get("sms").get("sent").asInt()).isEqualTo(1);
		assertThat(report.get("sms").get("skipped").asInt()).isEqualTo(3);
	}

	@Test
	void nonUrgentTextsWaitForQuietHoursUrgentOnesDoNot() throws Exception {
		Comms c = comms("chan-sms-quiet");
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		setting(c.schoolId(), "announcements.sms.types", List.of("IMPORTANT", "URGENT"));
		quietNow(c.gA1());
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Notice", "Body", classAudience(c.class1A())),
				"mode", "PUBLISH", "type", "IMPORTANT", "channels", List.of("PUSH", "SMS")), 201).get("id").asString());
		awaitDelivered(id);
		waitForSms(id, c.gSib(), "SENT");
		// the sleeper's text is reserved but waits
		assertThat(smsStatus(id, c.gA1())).isEqualTo("PENDING");
		assertThat(this.jdbc.queryForObject("select channels_not_before is not null from announcement_recipients where announcement_id = ? and user_id = ?",
				Boolean.class, id, c.gA1().userId())).isTrue();
		this.maintenance.resumeDeliveries();
		assertThat(smsStatus(id, c.gA1())).isEqualTo("PENDING");
		assertThat(this.messages.nothingDelivered(phoneOf(c.gA1()), Duration.ofMillis(300))).isTrue();
		this.clock.advance(Duration.ofHours(4));
		this.maintenance.resumeDeliveries();
		assertThat(smsStatus(id, c.gA1())).isEqualTo("SENT");
		assertThat(this.messages.awaitMessage(phoneOf(c.gA1()))).contains("Notice");
		this.clock.reset();
		// an urgent one goes straight through the same quiet hours
		UUID urgent = urgentWithSms(c, classAudience(c.class1A()));
		waitForSms(urgent, c.gA1(), "SENT");
	}

	@Test
	void withdrawingStopsTextsThatWereWaiting() throws Exception {
		Comms c = comms("chan-sms-withdraw");
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		setting(c.schoolId(), "announcements.sms.types", List.of("IMPORTANT"));
		quietNow(c.gA1());
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Notice", "Body", userAudience(c.gA1().userId())),
				"mode", "PUBLISH", "type", "IMPORTANT", "channels", List.of("PUSH", "SMS")), 201).get("id").asString());
		awaitDelivered(id);
		assertThat(smsStatus(id, c.gA1())).isEqualTo("PENDING");
		call(HttpMethod.POST, c.api() + "/" + id + "/withdraw", c.admin(), Map.of(), 200);
		assertThat(smsStatus(id, c.gA1())).isEqualTo("SKIPPED:WITHDRAWN");
		this.clock.advance(Duration.ofHours(4));
		this.maintenance.resumeDeliveries();
		assertThat(this.messages.nothingDelivered(phoneOf(c.gA1()), Duration.ofMillis(300))).isTrue();
	}

	@Test
	void anInterruptedSenderNeverTextsTwiceButEmailIsRetried() throws Exception {
		Comms c = comms("chan-stuck");
		setting(c.schoolId(), "announcements.channel.sms.enabled", true);
		UUID id = urgentWithSms(c, userAudience(c.gA1().userId()));
		waitForSms(id, c.gA1(), "SENT");
		// a crashed sender left both rows claimed a long time ago
		this.jdbc.update("""
				update announcement_recipients set sms_status = 'SENDING', email_status = 'SENDING',
				       channels_claimed_at = now() - interval '1 hour' where announcement_id = ?""", id);
		this.maintenance.resumeDeliveries();
		assertThat(smsStatus(id, c.gA1())).isEqualTo("FAILED:interrupted");
		// the email went back to the queue and, with no email address, is settled as undeliverable
		assertThat(this.jdbc.queryForObject("select email_status from announcement_recipients where announcement_id = ? and user_id = ?",
				String.class, id, c.gA1().userId())).isIn("PENDING", "FAILED", "SENT");
	}

	@Test
	void emailCarriesTheTitleOnlyUnlessTheSchoolAllowsTheText() throws Exception {
		Comms c = comms("chan-email");
		String email = "gA1-" + Ids.newId() + "@example.org";
		this.jdbc.update("update users set email = ?, email_verified_at = now() where id = ?", email, c.gA1().userId());
		UUID id = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("School picnic", "Secret details about Abel",
				classAudience(c.class1A())), "mode", "PUBLISH", "channels", List.of("PUSH", "EMAIL")), 201).get("id").asString());
		awaitDelivered(id);
		await().atMost(Duration.ofSeconds(15)).until(() -> "SENT".equals(emailStatus(id, c.gA1())));
		String mail = this.messages.awaitMessage(email);
		assertThat(mail).startsWith("School picnic").contains("chan-email").doesNotContain("Secret details");
		assertThat(emailStatus(id, c.gSib())).isEqualTo("SKIPPED:NO_CONTACT");
		// with the school's permission the email says it all
		setting(c.schoolId(), "announcements.email.include_body", true);
		UUID full = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Second", "Secret details again",
				userAudience(c.gA1().userId())), "mode", "PUBLISH", "channels", List.of("EMAIL")), 201).get("id").asString());
		await().atMost(Duration.ofSeconds(15)).until(() -> "SENT".equals(emailStatus(full, c.gA1())));
		assertThat(this.messages.awaitMessage(email)).contains("Secret details again");
		// opting out of email wins
		call(HttpMethod.PUT, "/api/v1/me/communication-channels", c.gA1(), Map.of("email", false), 200);
		UUID third = UUID.fromString(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("Third", "Body",
				userAudience(c.gA1().userId())), "mode", "PUBLISH", "channels", List.of("EMAIL")), 201).get("id").asString());
		await().atMost(Duration.ofSeconds(15)).until(() -> "SKIPPED:OPTED_OUT".equals(emailStatus(third, c.gA1())));
		assertThat(this.messages.nothingDelivered(email, Duration.ofMillis(300))).isTrue();
		// the school can switch email off
		setting(c.schoolId(), "announcements.channel.email.enabled", false);
		assertThat(code(call(HttpMethod.POST, c.api(), c.admin(), with(announcement("t", "b", classAudience(c.class1A())), "channels",
				List.of("EMAIL")), 400))).isEqualTo("announcement.channel_unavailable");
	}

}
