package com.nemaz.school.communications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.tx.AdvisoryLocks;
import com.nemaz.school.communications.SmsAdapter;
import com.nemaz.school.communications.SmsAdapter.SmsMessage;
import com.nemaz.school.communications.SmsAdapter.SmsResult;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementChannel;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserPreferences;
import com.nemaz.school.identity.UserDirectory.VerifiedContacts;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.system.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The optional channels of an announcement, email and SMS, in two steps so that nothing is ever sent twice by accident and
 * nothing expensive is sent without a decision on record.
 *
 * <p>
 * <b>Plan</b> (in the delivery chunk's transaction): for each recipient the row gets {@code email_status} and
 * {@code sms_status}: SKIPPED with a reason (no verified contact, the person opted out, the number opted out with the
 * provider, over the per-announcement or the per-school daily cap) or PENDING. The caps are counted under an advisory lock
 * per school, so concurrent announcements cannot overshoot them. A non-urgent announcement waits for the end of the
 * recipient's quiet hours ({@code channels_not_before}); URGENT and SAFETY do not, but opt-outs always apply.
 *
 * <p>
 * <b>Send</b> ({@link #sendDue}): a batch of PENDING rows is claimed (SENDING, committed), sent outside any transaction,
 * and the outcomes written back. SMS is at-most-once (a row stuck in SENDING after a crash becomes FAILED
 * {@code interrupted}, never resent: it costs money); email is at-least-once (a stuck row goes back to PENDING).
 * What an email or SMS says is minimal on purpose: the school and the title, and the full text only in emails of schools
 * that switched {@code announcements.email.include_body} on.
 */
@Component
class ChannelDispatcher {

	static final int BATCH = 100;

	private static final Logger log = LoggerFactory.getLogger(ChannelDispatcher.class);

	private static final int SMS_TITLE_MAX = 80;

	private final JdbcClient jdbc;

	private final UserDirectory users;

	private final ChannelPreferences preferences;

	private final CommunicationsSettings settings;

	private final NotificationService notifications;

	private final SmsAdapterRegistry registry;

	private final EmailSender email;

	private final MessageSource messages;

	private final AnnouncementRows rows;

	private final Clock clock;

	private final TransactionTemplate tx;

	ChannelDispatcher(JdbcClient jdbc, UserDirectory users, ChannelPreferences preferences, CommunicationsSettings settings,
			NotificationService notifications, SmsAdapterRegistry registry, EmailSender email, MessageSource messages,
			AnnouncementRows rows, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.users = users;
		this.preferences = preferences;
		this.settings = settings;
		this.notifications = notifications;
		this.registry = registry;
		this.email = email;
		this.messages = messages;
		this.rows = rows;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** One recipient of the chunk being delivered. */
	record Target(UUID rowId, UUID userId) {
	}

	// --- plan -----------------------------------------------------------------------------

	/** Decides, in the caller's transaction, what email and SMS each recipient of the chunk gets. */
	void plan(AnnRow ann, List<Target> targets, Instant now) {
		boolean wantEmail = ann.channels().contains(AnnouncementChannel.EMAIL) && this.settings.emailAvailable(ann.schoolId());
		boolean wantSms = ann.channels().contains(AnnouncementChannel.SMS)
				&& this.settings.smsAllowedFor(ann.schoolId(), ann.type());
		if (targets.isEmpty() || (!wantEmail && !wantSms)) {
			return;
		}
		List<UUID> ids = targets.stream().map(Target::userId).toList();
		Map<UUID, VerifiedContacts> contacts = this.users.verifiedContactsOf(ids);
		Set<UUID> emailOut = this.preferences.emailOptOuts(ids);
		Set<UUID> smsOut = this.preferences.smsOptOuts(ids);
		Set<String> blocked = this.preferences.blockedNumbers(contacts.values()
			.stream()
			.map(VerifiedContacts::phoneE164)
			.filter(java.util.Objects::nonNull)
			.toList());
		Map<UUID, Instant> quiet = ann.type().urgent() ? Map.of() : this.notifications.quietHoursEnds(ids);
		int capAnnouncement = this.settings.smsPerAnnouncement(ann.schoolId());
		int capDay = this.settings.smsPerDay(ann.schoolId());
		int usedAnnouncement = 0;
		int usedDay = 0;
		if (wantSms) {
			AdvisoryLocks.acquire(this.jdbc, "announcement-sms:" + ann.schoolId());
			usedAnnouncement = this.jdbc.sql("""
					select count(*) from announcement_recipients where announcement_id = :a
					  and sms_status in ('PENDING', 'SENDING', 'SENT')""").param("a", ann.id()).query(Integer.class).single();
			usedDay = this.jdbc.sql("""
					select count(*) from announcement_recipients where school_id = :s and sms_at >= :from
					  and sms_status in ('PENDING', 'SENDING', 'SENT')""")
				.param("s", ann.schoolId())
				.param("from", Timestamp.from(startOfDay(ann.schoolId(), now)))
				.query(Integer.class)
				.single();
		}
		for (Target target : targets) {
			VerifiedContacts contact = contacts.get(target.userId());
			String emailStatus = null;
			String emailReason = null;
			String smsStatus = null;
			String smsReason = null;
			if (wantEmail) {
				if (contact == null || contact.email() == null) {
					emailStatus = "SKIPPED";
					emailReason = "NO_CONTACT";
				}
				else if (emailOut.contains(target.userId())) {
					emailStatus = "SKIPPED";
					emailReason = "OPTED_OUT";
				}
				else {
					emailStatus = "PENDING";
				}
			}
			boolean smsReserved = false;
			if (wantSms) {
				if (contact == null || contact.phoneE164() == null) {
					smsStatus = "SKIPPED";
					smsReason = "NO_CONTACT";
				}
				else if (smsOut.contains(target.userId()) || blocked.contains(contact.phoneE164())) {
					smsStatus = "SKIPPED";
					smsReason = "OPTED_OUT";
				}
				else if (usedAnnouncement >= capAnnouncement) {
					smsStatus = "SKIPPED";
					smsReason = "CAP_ANNOUNCEMENT";
				}
				else if (usedDay >= capDay) {
					smsStatus = "SKIPPED";
					smsReason = "CAP_DAY";
				}
				else {
					smsStatus = "PENDING";
					smsReserved = true;
					usedAnnouncement++;
					usedDay++;
				}
			}
			Instant notBefore = ("PENDING".equals(emailStatus) || smsReserved) ? quiet.get(target.userId()) : null;
			this.jdbc.sql("""
					update announcement_recipients set email_status = :es, email_reason = :er, sms_status = :ss, sms_reason = :sr,
					       sms_at = case when cast(:reserved as boolean) then cast(:now as timestamptz) end, channels_not_before = :nb
					where id = :id""")
				.param("es", emailStatus)
				.param("er", emailReason)
				.param("ss", smsStatus)
				.param("sr", smsReason)
				.param("reserved", smsReserved)
				.param("now", Timestamp.from(now))
				.param("nb", (notBefore != null) ? Timestamp.from(notBefore) : null, java.sql.Types.TIMESTAMP)
				.param("id", target.rowId())
				.update();
		}
	}

	/** Midnight of the school's local day containing {@code now} (the window of the daily SMS cap). */
	private Instant startOfDay(UUID schoolId, Instant now) {
		String zone = this.jdbc.sql("select timezone from schools where id = :id").param("id", schoolId).query(String.class).single();
		ZoneId id;
		try {
			id = ZoneId.of(zone);
		}
		catch (RuntimeException ex) {
			id = ZoneId.of("UTC");
		}
		LocalDate day = now.atZone(id).toLocalDate();
		return day.atStartOfDay(id).toInstant();
	}

	// --- send -----------------------------------------------------------------------------

	private record Claimed(UUID rowId, UUID userId, boolean email, boolean sms) {
	}

	private record Outcome(UUID rowId, String emailStatus, String emailReason, String smsStatus, String smsReason,
			String adapter, String providerRef) {
	}

	/** Sends the PENDING email and SMS of an announcement that are due; returns how many messages were attempted. */
	int sendDue(UUID announcementId) {
		int attempted = 0;
		while (true) {
			List<Claimed> claimed = this.tx.execute((status) -> claim(announcementId));
			if (claimed == null || claimed.isEmpty()) {
				return attempted;
			}
			AnnRow ann = this.jdbc.sql("select " + AnnouncementRows.COLUMNS + " from announcements an where an.id = :id")
				.param("id", announcementId)
				.query((rs, i) -> this.rows.map(rs))
				.single();
			String schoolName = this.jdbc.sql("select name from schools where id = :id")
				.param("id", ann.schoolId())
				.query(String.class)
				.single();
			List<UUID> ids = claimed.stream().map(Claimed::userId).toList();
			Map<UUID, VerifiedContacts> contacts = this.users.verifiedContactsOf(ids);
			Map<UUID, UserPreferences> prefs = this.users.preferences(ids);
			List<Outcome> outcomes = new ArrayList<>();
			for (Claimed c : claimed) {
				Locale locale = localeOf(prefs.get(c.userId()));
				VerifiedContacts contact = contacts.get(c.userId());
				String emailStatus = null;
				String emailReason = null;
				String smsStatus = null;
				String smsReason = null;
				String adapterId = null;
				String providerRef = null;
				if (c.email()) {
					attempted++;
					String error = sendEmail(ann, schoolName, contact, locale);
					emailStatus = (error == null) ? "SENT" : "FAILED";
					emailReason = error;
				}
				if (c.sms()) {
					attempted++;
					SmsOutcome out = sendSms(ann, schoolName, c.rowId(), contact, locale);
					smsStatus = out.error() == null ? "SENT" : "FAILED";
					smsReason = out.error();
					adapterId = out.adapter();
					providerRef = out.providerRef();
				}
				outcomes.add(new Outcome(c.rowId(), emailStatus, emailReason, smsStatus, smsReason, adapterId, providerRef));
			}
			this.tx.executeWithoutResult((status) -> record(outcomes));
		}
	}

	/** Takes up to {@link #BATCH} due rows: SMS rows become SENDING, so no second sender can send them. */
	private List<Claimed> claim(UUID announcementId) {
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		List<Claimed> claimed = this.jdbc.sql("""
				select id, user_id, (email_status = 'PENDING') as email, (sms_status = 'PENDING') as sms
				from announcement_recipients
				where announcement_id = :a and (email_status = 'PENDING' or sms_status = 'PENDING')
				  and (channels_not_before is null or channels_not_before <= :now)
				order by id limit :n for update skip locked""")
			.param("a", announcementId)
			.param("now", Timestamp.from(now))
			.param("n", BATCH)
			.query((rs, i) -> new Claimed(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id"), rs.getBoolean("email"),
					rs.getBoolean("sms")))
			.list();
		if (!claimed.isEmpty()) {
			this.jdbc.sql("""
					update announcement_recipients set channels_claimed_at = :now,
					       email_status = case when email_status = 'PENDING' then 'SENDING' else email_status end,
					       sms_status = case when sms_status = 'PENDING' then 'SENDING' else sms_status end
					where id in (:ids)""")
				.param("now", Timestamp.from(now))
				.param("ids", claimed.stream().map(Claimed::rowId).toList())
				.update();
		}
		return claimed;
	}

	private void record(List<Outcome> outcomes) {
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		for (Outcome o : outcomes) {
			this.jdbc.sql("""
					update announcement_recipients set
					  email_status = coalesce(:es, email_status), email_reason = coalesce(:er, email_reason),
					  email_sent_at = case when :es = 'SENT' then :now else email_sent_at end,
					  sms_status = coalesce(:ss, sms_status), sms_reason = coalesce(:sr, sms_reason),
					  sms_adapter = coalesce(:adapter, sms_adapter), sms_provider_ref = coalesce(:ref, sms_provider_ref)
					where id = :id""")
				.param("es", o.emailStatus())
				.param("er", o.emailReason())
				.param("ss", o.smsStatus())
				.param("sr", o.smsReason())
				.param("adapter", o.adapter())
				.param("ref", o.providerRef())
				.param("now", now)
				.param("id", o.rowId())
				.update();
		}
	}

	/** Null when the email was handed to the mail sender, else a short reason (never an address). */
	private String sendEmail(AnnRow ann, String schoolName, VerifiedContacts contact, Locale locale) {
		if (contact == null || contact.email() == null) {
			return "NO_CONTACT";
		}
		if (!this.email.configured()) {
			return "NOT_CONFIGURED";
		}
		String title = titleFor(ann, locale);
		String subject = this.messages.getMessage("announcement.email.subject", new Object[] { title }, locale);
		String text = this.settings.emailIncludesBody(ann.schoolId())
				? this.messages.getMessage("announcement.email.body_with_text",
						new Object[] { schoolName, title, bodyFor(ann, locale) }, locale)
				: this.messages.getMessage("announcement.email.body", new Object[] { schoolName, title }, locale);
		try {
			this.email.send(contact.email(), BodyText.oneLine(subject, 150), text);
			return null;
		}
		catch (RuntimeException ex) {
			log.warn("Announcement email failed: {}", ex.getClass().getSimpleName());
			return "SEND_ERROR";
		}
	}

	private record SmsOutcome(String error, String adapter, String providerRef) {
	}

	private SmsOutcome sendSms(AnnRow ann, String schoolName, UUID rowId, VerifiedContacts contact, Locale locale) {
		if (contact == null || contact.phoneE164() == null) {
			return new SmsOutcome("NO_CONTACT", null, null);
		}
		SmsAdapter adapter = this.registry.forNumber(contact.phoneE164()).orElse(null);
		if (adapter == null) {
			return new SmsOutcome("no_adapter", null, null);
		}
		String text = this.messages.getMessage("announcement.sms.text",
				new Object[] { schoolName, BodyText.oneLine(titleFor(ann, locale), SMS_TITLE_MAX) }, locale);
		try {
			SmsResult result = adapter.send(new SmsMessage(contact.phoneE164(), text, locale.getLanguage(), null,
					rowId.toString()));
			return result.accepted() ? new SmsOutcome(null, adapter.id(), result.providerRef())
					: new SmsOutcome(truncate(result.error()), adapter.id(), null);
		}
		catch (RuntimeException ex) {
			log.warn("SMS adapter {} failed: {}", adapter.id(), ex.getClass().getSimpleName());
			return new SmsOutcome("adapter_error", adapter.id(), null);
		}
	}

	private static String truncate(String error) {
		if (error == null) {
			return "failed";
		}
		return (error.length() <= 120) ? error : error.substring(0, 120);
	}

	static String titleFor(AnnRow ann, Locale locale) {
		var translation = ann.translations().get(locale.getLanguage());
		return (translation != null && !locale.getLanguage().equals(ann.languageCode())) ? translation.title() : ann.title();
	}

	static String bodyFor(AnnRow ann, Locale locale) {
		var translation = ann.translations().get(locale.getLanguage());
		return (translation != null && !locale.getLanguage().equals(ann.languageCode())) ? translation.body() : ann.body();
	}

	private static Locale localeOf(UserPreferences preferences) {
		String language = (preferences != null) ? preferences.languageCode() : null;
		return ("am".equals(language)) ? Locale.forLanguageTag("am") : Locale.ENGLISH;
	}

	// --- recovery -------------------------------------------------------------------------

	/**
	 * Frees rows a crashed sender left SENDING for over 15 minutes: email goes back to PENDING (sent again), SMS becomes
	 * FAILED {@code interrupted} (never resent: it may have gone out). Returns how many rows were touched.
	 */
	int recoverStuck() {
		Timestamp cutoff = Timestamp.from(this.clock.instant().minusSeconds(15 * 60L));
		// SMS first: the email update clears channels_claimed_at of the same rows
		int sms = this.jdbc.sql("""
				update announcement_recipients set sms_status = 'FAILED', sms_reason = 'interrupted'
				where sms_status = 'SENDING' and channels_claimed_at < :cutoff""").param("cutoff", cutoff).update();
		int emails = this.jdbc.sql("""
				update announcement_recipients set email_status = 'PENDING', channels_claimed_at = null
				where email_status = 'SENDING' and channels_claimed_at < :cutoff""").param("cutoff", cutoff).update();
		return emails + sms;
	}

	/** Announcements that have email or SMS due (for the resume job). */
	List<UUID> announcementsWithDueChannels(int limit) {
		return this.jdbc.sql("""
				select distinct announcement_id from announcement_recipients
				where (email_status = 'PENDING' or sms_status = 'PENDING')
				  and (channels_not_before is null or channels_not_before <= :now) limit :n""")
			.param("now", Timestamp.from(this.clock.instant()))
			.param("n", limit)
			.query(UUID.class)
			.list();
	}

}
