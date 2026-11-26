package com.nemaz.school.communications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.communications.AnnouncementNoticeRequested;
import com.nemaz.school.communications.AnnouncementPublished;
import com.nemaz.school.communications.application.AnnouncementAccess.Basis;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementAudienceInput;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementChannel;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementPreview;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementRequest;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementTranslation;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementType;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementUpdateRequest;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementView;
import com.nemaz.school.communications.application.AnnouncementModels.AudienceType;
import com.nemaz.school.communications.application.AnnouncementModels.NoticeKind;
import com.nemaz.school.communications.application.AnnouncementModels.PublishMode;
import com.nemaz.school.communications.application.AnnouncementModels.RecipientRole;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lifecycle of an announcement: draft, schedule, publish, edit (with revisions), withdraw, delete a draft. Every
 * change is audited in its own transaction with ids, type, audience summary and counts only (never the title or text:
 * they may name children), and publishes its event there.
 *
 * <p>
 * <b>State machine.</b> DRAFT becomes SCHEDULED ({@link #schedule}, back with {@link #unschedule}) or PUBLISHED
 * ({@link #publish}, or the scheduler through {@link #publishDue}); a SCHEDULED announcement whose author may no longer
 * send it at its time returns to DRAFT with {@code schedule_error_code} and the author is told. PUBLISHED becomes
 * WITHDRAWN ({@link #withdraw}): the row stays as an audited tombstone (title, who and when, the public note), only the
 * body is hidden from readers. Drafts and scheduled announcements can be deleted (soft). After publishing only the text
 * can change; each edit stores the previous version in {@code announcement_revisions}, and an edit the editor marks
 * {@code material} tells the recipients.
 */
@Service
public class AnnouncementService {

	static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(180);

	static final int PUBLISH_LIMIT_PER_HOUR = 30;

	private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$");

	private final JdbcClient jdbc;

	private final AnnouncementAccess access;

	private final AnnouncementRows rows;

	private final AnnouncementViews views;

	private final AudienceResolver resolver;

	private final CommunicationsSettings settings;

	private final NotificationService notifications;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final TransactionTemplate newTransaction;

	AnnouncementService(JdbcClient jdbc, AnnouncementAccess access, AnnouncementRows rows, AnnouncementViews views,
			AudienceResolver resolver, CommunicationsSettings settings, NotificationService notifications,
			RateLimiter limiter, AuditLog audit, ApplicationEventPublisher events, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.jdbc = jdbc;
		this.access = access;
		this.rows = rows;
		this.views = views;
		this.resolver = resolver;
		this.settings = settings;
		this.notifications = notifications;
		this.limiter = limiter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	// --- create -------------------------------------------------------------------------------

	@Transactional
	public AnnouncementView create(CurrentUser user, UUID schoolId, AnnouncementRequest req) {
		this.access.requireWrite(user, schoolId);
		AnnouncementType type = (req.type() != null) ? req.type() : AnnouncementType.NORMAL;
		boolean ack = Boolean.TRUE.equals(req.requiresAcknowledgement());
		requireAckAllowed(type, ack);
		Set<AnnouncementChannel> channels = channels(req.channels());
		requireChannelsAvailable(schoolId, type, channels);
		Content content = content(req.title(), req.body(), req.bodyLanguageCode(), req.translations());
		List<AnnouncementAudienceInput> audiences = req.audiences();
		Basis basis = this.access.authorize(user, schoolId, type, channels, audiences);
		PublishMode mode = (req.mode() != null) ? req.mode() : PublishMode.DRAFT;
		Instant scheduledFor = (mode == PublishMode.SCHEDULE) ? checkSchedule(req.scheduledFor()) : null;
		UUID id = Ids.newId();
		Instant now = now();
		this.jdbc.sql("""
				insert into announcements (id, school_id, author_id, title, body, body_language_code, translations,
				                           announcement_type, requires_acknowledgement, channels, authored_as, created_at,
				                           updated_at)
				values (:id, :school, :author, :title, :body, :lang, cast(:translations as jsonb), :type, :ack,
				        string_to_array(:channels, ','), :basis, :now, :now)""")
			.param("id", id)
			.param("school", schoolId)
			.param("author", user.userId())
			.param("title", content.title())
			.param("body", content.body())
			.param("lang", content.languageCode())
			.param("translations", this.rows.toJson(content.translations()))
			.param("type", type.name())
			.param("ack", ack)
			.param("channels", channelList(channels))
			.param("basis", basis.name())
			.param("now", Timestamp.from(now))
			.update();
		insertAudiences(id, audiences);
		record(user, "announcement.create", id, schoolId, audit(type, audiences).with("mode", mode.name()));
		if (mode == PublishMode.PUBLISH) {
			publishLocked(user, lock(schoolId, id), true);
		}
		else if (mode == PublishMode.SCHEDULE) {
			this.jdbc.sql("update announcements set status = 'SCHEDULED', scheduled_for = :at where id = :id")
				.param("at", Timestamp.from(scheduledFor))
				.param("id", id)
				.update();
			record(user, "announcement.schedule", id, schoolId, new Meta().with("scheduledFor", scheduledFor.toString()));
		}
		return this.views.view(user, schoolId, id);
	}

	// --- edit ---------------------------------------------------------------------------------

	@Transactional
	public AnnouncementView update(CurrentUser user, UUID schoolId, UUID id, AnnouncementUpdateRequest req) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() == AnnouncementStatus.WITHDRAWN) {
			throw ApiException.conflict("announcement.withdrawn");
		}
		boolean published = row.status() == AnnouncementStatus.PUBLISHED;
		if (published && (req.type() != null || req.requiresAcknowledgement() != null || req.channels() != null
				|| req.audiences() != null)) {
			throw ApiException.conflict("announcement.locked_after_publish");
		}
		String title = (req.title() != null) ? BodyText.required(req.title(), BodyText.TITLE_MAX, "title") : row.title();
		String body = (req.body() != null) ? BodyText.required(req.body(), BodyText.BODY_MAX, "body") : row.body();
		String lang = (req.bodyLanguageCode() != null) ? language(req.bodyLanguageCode()) : row.languageCode();
		Map<String, AnnouncementTranslation> translations = (req.translations() != null)
				? translations(req.translations(), lang) : row.translations();
		List<String> changed = new ArrayList<>();
		if (!title.equals(row.title())) {
			changed.add("title");
		}
		if (!body.equals(row.body())) {
			changed.add("body");
		}
		if (!translations.equals(row.translations())) {
			changed.add("translations");
		}
		if (java.util.Objects.equals(lang, row.languageCode()) == false) {
			changed.add("language");
		}
		AnnouncementType type = (req.type() != null) ? req.type() : row.type();
		boolean ack = (req.requiresAcknowledgement() != null) ? req.requiresAcknowledgement() : row.requiresAcknowledgement();
		Set<AnnouncementChannel> channels = (req.channels() != null) ? channels(req.channels())
				: new LinkedHashSet<>(row.channels());
		List<AnnouncementAudienceInput> audiences = (req.audiences() != null) ? req.audiences() : audiencesOf(id);
		Basis basis = row.basis();
		if (!published) {
			requireAckAllowed(type, ack);
			requireChannelsAvailable(schoolId, type, channels);
			basis = this.access.authorize(user, schoolId, type, channels, audiences);
			if (type != row.type()) {
				changed.add("type");
			}
			if (ack != row.requiresAcknowledgement()) {
				changed.add("acknowledgement");
			}
			if (req.channels() != null) {
				changed.add("channels");
			}
			if (req.audiences() != null) {
				changed.add("audiences");
				this.jdbc.sql("delete from announcement_audiences where announcement_id = :id").param("id", id).update();
				insertAudiences(id, audiences);
			}
		}
		if (changed.isEmpty()) {
			return this.views.view(user, schoolId, id);
		}
		Instant now = now();
		boolean material = false;
		if (published) {
			if (req.material() == null) {
				throw ApiException.badRequest("announcement.material_required");
			}
			material = req.material();
			String note = BodyText.optional(req.changeNote(), 300, "change_note");
			this.jdbc.sql("""
					insert into announcement_revisions (announcement_id, revision, title, body, translations, material,
					                                    change_note, edited_by, edited_at)
					values (:id, :rev, :title, :body, cast(:translations as jsonb), :material, :note, :me, :now)""")
				.param("id", id)
				.param("rev", row.revision())
				.param("title", row.title())
				.param("body", row.body())
				.param("translations", this.rows.toJson(row.translations()))
				.param("material", material)
				.param("note", note)
				.param("me", user.userId())
				.param("now", Timestamp.from(now))
				.update();
		}
		this.jdbc.sql("""
				update announcements set title = :title, body = :body, body_language_code = :lang,
				       translations = cast(:translations as jsonb), announcement_type = :type, requires_acknowledgement = :ack,
				       channels = string_to_array(:channels, ','), authored_as = :basis,
				       revision = revision + :bump, updated_at = :now, version = version + 1
				where id = :id""")
			.param("title", title)
			.param("body", body)
			.param("lang", lang)
			.param("translations", this.rows.toJson(translations))
			.param("type", type.name())
			.param("ack", ack)
			.param("channels", channelList(channels))
			.param("basis", basis.name())
			.param("bump", published ? 1 : 0)
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
		Meta entry = new Meta().with("status", row.status().name()).with("fields", changed);
		if (published) {
			entry.with("revision", row.revision() + 1).with("material", material);
		}
		record(user, "announcement.update", id, schoolId, entry);
		if (published && material) {
			requestNotice(user, row, NoticeKind.UPDATED, row.revision() + 1, countNotified(id));
		}
		return this.views.view(user, schoolId, id);
	}

	// --- schedule and publish -----------------------------------------------------------------

	@Transactional
	public AnnouncementView schedule(CurrentUser user, UUID schoolId, UUID id, Instant scheduledFor) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() != AnnouncementStatus.DRAFT && row.status() != AnnouncementStatus.SCHEDULED) {
			throw ApiException.conflict("announcement.invalid_state");
		}
		Instant at = checkSchedule(scheduledFor);
		this.access.authorize(user, schoolId, row.type(), new LinkedHashSet<>(row.channels()), audiencesOf(id));
		requireChannelsAvailable(schoolId, row.type(), new LinkedHashSet<>(row.channels()));
		this.jdbc.sql("""
				update announcements set status = 'SCHEDULED', scheduled_for = :at, schedule_error_code = null,
				       updated_at = :now, version = version + 1 where id = :id""")
			.param("at", Timestamp.from(at))
			.param("now", Timestamp.from(now()))
			.param("id", id)
			.update();
		record(user, "announcement.schedule", id, schoolId, new Meta().with("scheduledFor", at.toString()));
		return this.views.view(user, schoolId, id);
	}

	@Transactional
	public AnnouncementView unschedule(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() == AnnouncementStatus.DRAFT) {
			return this.views.view(user, schoolId, id);
		}
		if (row.status() != AnnouncementStatus.SCHEDULED) {
			throw ApiException.conflict("announcement.invalid_state");
		}
		this.jdbc.sql("""
				update announcements set status = 'DRAFT', scheduled_for = null, updated_at = :now, version = version + 1
				where id = :id""").param("now", Timestamp.from(now())).param("id", id).update();
		record(user, "announcement.unschedule", id, schoolId, new Meta());
		return this.views.view(user, schoolId, id);
	}

	/** Publishes now. Idempotent: an already PUBLISHED announcement is returned unchanged. */
	@Transactional
	public AnnouncementView publish(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() == AnnouncementStatus.PUBLISHED) {
			return this.views.view(user, schoolId, id);
		}
		publishLocked(user, row, true);
		return this.views.view(user, schoolId, id);
	}

	/** How many people the announcement would reach now (a draft's audience, resolved but not saved). */
	@Transactional
	public AnnouncementPreview preview(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() == AnnouncementStatus.PUBLISHED || row.status() == AnnouncementStatus.WITHDRAWN) {
			throw ApiException.conflict("announcement.invalid_state");
		}
		return this.resolver.preview(id, this.settings.studentsEnabled(schoolId));
	}

	/**
	 * Resolves the recipients, flips the status and publishes the event, in the caller's transaction. {@code actor} is the
	 * person publishing (the author for the scheduler); the audience is authorized again against them, so a teacher removed
	 * from the class since drafting cannot publish to it.
	 */
	private void publishLocked(CurrentUser actor, AnnRow row, boolean interactive) {
		if (row.status() == AnnouncementStatus.WITHDRAWN) {
			throw ApiException.conflict("announcement.withdrawn");
		}
		UUID id = row.id();
		UUID schoolId = row.schoolId();
		List<AnnouncementAudienceInput> audiences = audiencesOf(id);
		Set<AnnouncementChannel> channels = new LinkedHashSet<>(row.channels());
		Basis basis = this.access.authorize(actor, schoolId, row.type(), channels, audiences);
		Set<AnnouncementChannel> effective = interactive ? requireChannelsAvailable(schoolId, row.type(), channels)
				: availableChannels(schoolId, row.type(), channels);
		if (interactive) {
			this.limiter.consume("announcement-publish", actor.userId().toString(), PUBLISH_LIMIT_PER_HOUR,
					Duration.ofHours(1), "announcement.rate_limited");
		}
		this.jdbc.sql("delete from announcement_recipients where announcement_id = :id").param("id", id).update();
		int recipients = this.resolver.snapshot(id, this.settings.studentsEnabled(schoolId));
		if (recipients == 0) {
			throw ApiException.unprocessable("announcement.no_recipients");
		}
		Instant now = now();
		this.jdbc.sql("""
				update announcements set status = 'PUBLISHED', published_at = :now, scheduled_for = null,
				       schedule_error_code = null, recipient_count = :n, channels = string_to_array(:channels, ','),
				       authored_as = :basis, delivery_completed_at = null, updated_at = :now, version = version + 1
				where id = :id""")
			.param("now", Timestamp.from(now))
			.param("n", recipients)
			.param("channels", channelList(effective))
			.param("basis", basis.name())
			.param("id", id)
			.update();
		Meta entry = audit(row.type(), audiences).with("recipients", recipients)
			.with("ack", row.requiresAcknowledgement())
			.with("channels", channelList(effective));
		if (!effective.equals(channels)) {
			entry.with("channelsDropped", true);
		}
		if (!interactive) {
			entry.with("scheduled", true);
		}
		record(actor, "announcement.publish", id, schoolId, entry);
		this.events.publishEvent(new AnnouncementPublished(id, schoolId));
	}

	// --- the scheduler ------------------------------------------------------------------------

	/** Ids of the SCHEDULED announcements whose time has come (oldest first). */
	@Transactional(readOnly = true)
	public List<UUID> dueIds(int limit) {
		return this.jdbc.sql("""
				select id from announcements where status = 'SCHEDULED' and scheduled_for <= :now and deleted_at is null
				order by scheduled_for, id limit :n""")
			.param("now", Timestamp.from(now()))
			.param("n", limit)
			.query(UUID.class)
			.list();
	}

	/**
	 * Publishes one due announcement as its author. Idempotent: it does nothing unless the announcement is still SCHEDULED
	 * and due (a second instance, a rerun or a withdrawal in between), and the row lock makes two runs publish once. When
	 * the author may no longer send it (or nobody would receive it) it goes back to DRAFT with the reason and the author is
	 * told. Called without a transaction: each step has its own.
	 */
	public boolean publishDue(UUID id) {
		try {
			return inNewTransaction(() -> {
				AnnRow row = lockForJob(id);
				if (row == null) {
					return false;
				}
				publishLocked(this.access.userFor(row.authorId()), row, false);
				return true;
			});
		}
		catch (ApiException ex) {
			inNewTransaction(() -> {
				blockScheduled(id, ex.code());
				return true;
			});
			return false;
		}
	}

	private <T> T inNewTransaction(java.util.function.Supplier<T> work) {
		return this.newTransaction.execute((status) -> work.get());
	}

	private AnnRow lockForJob(UUID id) {
		return this.jdbc.sql("select " + AnnouncementRows.COLUMNS
				+ " from announcements an where an.id = :id and an.status = 'SCHEDULED' and an.scheduled_for <= :now"
				+ " and an.deleted_at is null for update skip locked")
			.param("id", id)
			.param("now", Timestamp.from(now()))
			.query((rs, i) -> this.rows.map(rs))
			.optional()
			.orElse(null);
	}

	private void blockScheduled(UUID id, String code) {
		AnnRow row = this.jdbc.sql("select " + AnnouncementRows.COLUMNS
				+ " from announcements an where an.id = :id and an.status = 'SCHEDULED' for update")
			.param("id", id)
			.query((rs, i) -> this.rows.map(rs))
			.optional()
			.orElse(null);
		if (row == null) {
			return;
		}
		this.jdbc.sql("""
				update announcements set status = 'DRAFT', scheduled_for = null, schedule_error_code = :code, updated_at = :now,
				       version = version + 1 where id = :id""")
			.param("code", code)
			.param("now", Timestamp.from(now()))
			.param("id", id)
			.update();
		this.audit.record(AuditEntry.of("announcement.publish_blocked", AuditOutcome.FAILURE)
			.actor(row.authorId())
			.target("announcement", id)
			.school(row.schoolId())
			.metadata("code", code)
			.metadata("type", row.type().name()));
		this.notifications.notify(com.nemaz.school.notifications.NotificationRequest
			.to("ANNOUNCEMENT_PUBLISH_BLOCKED", row.authorId())
			.args(com.nemaz.school.notifications.LocalizedText.of(titleMap(row)))
			.school(row.schoolId())
			.source("ANNOUNCEMENT_BLOCKED", id)
			.path("/announcements/" + id)
			.data("announcementId", id.toString())
			.build());
	}

	// --- withdraw and delete ------------------------------------------------------------------

	/** Withdraws a PUBLISHED announcement; idempotent for an already withdrawn one. The row stays as a tombstone. */
	@Transactional
	public AnnouncementView withdraw(CurrentUser user, UUID schoolId, UUID id, String reason) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() == AnnouncementStatus.WITHDRAWN) {
			return this.views.view(user, schoolId, id);
		}
		if (row.status() != AnnouncementStatus.PUBLISHED) {
			throw ApiException.conflict("announcement.invalid_state");
		}
		String note = BodyText.optional(reason, BodyText.NOTE_MAX, "reason");
		Instant now = now();
		this.jdbc.sql("""
				update announcements set status = 'WITHDRAWN', withdrawn_at = :now, withdrawn_by = :me,
				       withdrawal_reason = :reason, updated_at = :now, version = version + 1 where id = :id""")
			.param("now", Timestamp.from(now))
			.param("me", user.userId())
			.param("reason", note)
			.param("id", id)
			.update();
		// pushes held back for quiet hours must not ring a phone for a withdrawn announcement
		int cancelled = this.notifications.cancelHeldBackPushes("ANNOUNCEMENT", id);
		for (UUID notice : this.jdbc.sql("select id from announcement_notices where announcement_id = :id and kind <> 'WITHDRAWN'")
			.param("id", id)
			.query(UUID.class)
			.list()) {
			cancelled += this.notifications.cancelHeldBackPushes("ANNOUNCEMENT_NOTICE", notice);
		}
		// email and SMS that were not sent yet are not sent for a withdrawn announcement
		this.jdbc.sql("""
				update announcement_recipients set
				  email_status = case when email_status = 'PENDING' then 'SKIPPED' else email_status end,
				  email_reason = case when email_status = 'PENDING' then 'WITHDRAWN' else email_reason end,
				  sms_status = case when sms_status = 'PENDING' then 'SKIPPED' else sms_status end,
				  sms_reason = case when sms_status = 'PENDING' then 'WITHDRAWN' else sms_reason end
				where announcement_id = :id and (email_status = 'PENDING' or sms_status = 'PENDING')""")
			.param("id", id)
			.update();
		record(user, "announcement.withdraw", id, schoolId, new Meta().with("type", row.type().name())
			.with("recipients", row.recipientCount())
			.with("hasReason", note != null)
			.with("heldPushesCancelled", cancelled));
		requestNotice(user, row, NoticeKind.WITHDRAWN, row.revision(), countNotified(id));
		return this.views.view(user, schoolId, id);
	}

	@Transactional
	public void delete(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() != AnnouncementStatus.DRAFT && row.status() != AnnouncementStatus.SCHEDULED) {
			throw ApiException.conflict("announcement.invalid_state");
		}
		this.jdbc.sql("""
				update announcements set status = 'DRAFT', scheduled_for = null, deleted_at = :now, updated_at = :now,
				       version = version + 1 where id = :id""").param("now", Timestamp.from(now())).param("id", id).update();
		record(user, "announcement.delete", id, schoolId, new Meta().with("type", row.type().name()));
	}

	// --- notices ------------------------------------------------------------------------------

	/** Records "tell the recipients again" and publishes the event; the delivery runs after commit, in chunks. */
	UUID requestNotice(CurrentUser user, AnnRow row, NoticeKind kind, Integer revision, int targeted) {
		UUID noticeId = Ids.newId();
		this.jdbc.sql("""
				insert into announcement_notices (id, announcement_id, school_id, kind, revision, requested_by, requested_at,
				                                  targeted, completed_at)
				values (:id, :ann, :school, :kind, :rev, :me, :now, :targeted, case when cast(:targeted as int) = 0 then cast(:now as timestamptz) end)""")
			.param("id", noticeId)
			.param("ann", row.id())
			.param("school", row.schoolId())
			.param("kind", kind.name())
			.param("rev", revision)
			.param("me", user.userId())
			.param("now", Timestamp.from(now()))
			.param("targeted", targeted)
			.update();
		if (targeted > 0) {
			this.events.publishEvent(new AnnouncementNoticeRequested(noticeId, row.id(), row.schoolId()));
		}
		return noticeId;
	}

	private int countNotified(UUID id) {
		return this.jdbc.sql("select count(*) from announcement_recipients where announcement_id = :id and notified_at is not null")
			.param("id", id)
			.query(Integer.class)
			.single();
	}

	// --- helpers ------------------------------------------------------------------------------

	AnnRow lock(UUID schoolId, UUID id) {
		return this.jdbc.sql("select " + AnnouncementRows.COLUMNS
				+ " from announcements an where an.id = :id and an.school_id = :school and an.deleted_at is null for update")
			.param("id", id)
			.param("school", schoolId)
			.query((rs, i) -> this.rows.map(rs))
			.optional()
			.orElseThrow(() -> ApiException.notFound("announcement.not_found"));
	}

	private Map<String, String> titleMap(AnnRow row) {
		Map<String, String> titles = new LinkedHashMap<>();
		titles.put((row.languageCode() != null) ? row.languageCode() : "und", row.title());
		row.translations().forEach((lang, t) -> titles.putIfAbsent(lang, t.title()));
		return titles;
	}

	private static void requireAckAllowed(AnnouncementType type, boolean ack) {
		if (ack && !type.mayRequireAcknowledgement()) {
			throw ApiException.badRequest("announcement.ack_not_allowed");
		}
	}

	private static Set<AnnouncementChannel> channels(Set<AnnouncementChannel> requested) {
		Set<AnnouncementChannel> set = new java.util.TreeSet<>();
		set.add(AnnouncementChannel.IN_APP);
		if (requested == null) {
			set.add(AnnouncementChannel.PUSH);
		}
		else {
			set.addAll(requested);
		}
		return set;
	}

	private static String channelList(Set<AnnouncementChannel> channels) {
		return String.join(",", channels.stream().map(Enum::name).toList());
	}

	/** 400 for a channel the school cannot use now (email without outbound email, SMS off or not for this type). */
	private Set<AnnouncementChannel> requireChannelsAvailable(UUID schoolId, AnnouncementType type,
			Set<AnnouncementChannel> channels) {
		if (channels.contains(AnnouncementChannel.EMAIL) && !this.settings.emailAvailable(schoolId)) {
			throw ApiException.badRequest("announcement.channel_unavailable");
		}
		if (channels.contains(AnnouncementChannel.SMS) && !this.settings.smsAllowedFor(schoolId, type)) {
			throw ApiException.badRequest("announcement.sms_not_allowed");
		}
		return channels;
	}

	/** The channels still allowed: what a scheduled publish keeps when settings changed after scheduling. */
	private Set<AnnouncementChannel> availableChannels(UUID schoolId, AnnouncementType type,
			Set<AnnouncementChannel> channels) {
		Set<AnnouncementChannel> set = new java.util.TreeSet<>(channels);
		if (!this.settings.emailAvailable(schoolId)) {
			set.remove(AnnouncementChannel.EMAIL);
		}
		if (!this.settings.smsAllowedFor(schoolId, type)) {
			set.remove(AnnouncementChannel.SMS);
		}
		return set;
	}

	private Instant checkSchedule(Instant scheduledFor) {
		if (scheduledFor == null) {
			throw ApiException.badRequest("announcement.schedule_required");
		}
		Instant now = now();
		if (!scheduledFor.isAfter(now)) {
			throw ApiException.badRequest("announcement.schedule_in_past");
		}
		if (scheduledFor.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
			throw ApiException.badRequest("announcement.schedule_too_far");
		}
		return scheduledFor.truncatedTo(ChronoUnit.MICROS);
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	record Content(String title, String body, String languageCode, Map<String, AnnouncementTranslation> translations) {
	}

	private Content content(String title, String body, String languageCode, Map<String, AnnouncementTranslation> translations) {
		String lang = language(languageCode);
		return new Content(BodyText.required(title, BodyText.TITLE_MAX, "title"),
				BodyText.required(body, BodyText.BODY_MAX, "body"), lang, translations(translations, lang));
	}

	/** A known language code (languages table) or null. */
	private String language(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		String normalized = code.strip().toLowerCase(java.util.Locale.ROOT);
		if (!LANGUAGE.matcher(normalized).matches() || !languageExists(normalized)) {
			throw ApiException.badRequest("announcement.language_invalid");
		}
		return normalized;
	}

	private boolean languageExists(String code) {
		return this.jdbc.sql("select exists (select 1 from languages where code = :c and is_enabled)")
			.param("c", code)
			.query(Boolean.class)
			.single();
	}

	/** Translations are written by people (D5): each needs a known language, other than the text's own, and clean text. */
	private Map<String, AnnouncementTranslation> translations(Map<String, AnnouncementTranslation> raw, String ownLanguage) {
		Map<String, AnnouncementTranslation> clean = new TreeMap<>();
		if (raw == null) {
			return clean;
		}
		raw.forEach((code, text) -> {
			String lang = language(code);
			if (lang == null || lang.equals(ownLanguage) || text == null) {
				throw ApiException.badRequest("announcement.language_invalid");
			}
			clean.put(lang, new AnnouncementTranslation(BodyText.required(text.title(), BodyText.TITLE_MAX, "title"),
					BodyText.required(text.body(), BodyText.BODY_MAX, "body")));
		});
		return clean;
	}

	private void insertAudiences(UUID id, List<AnnouncementAudienceInput> inputs) {
		Set<String> seen = new LinkedHashSet<>();
		for (AnnouncementAudienceInput input : inputs) {
			String roles = String.join(",", input.roles().stream().map(Enum::name).sorted().toList());
			if (input.type() == AudienceType.USER) {
				for (UUID userId : new LinkedHashSet<>(input.userIds())) {
					if (seen.add("USER:" + userId)) {
						insertAudience(id, input, userId, roles);
					}
				}
			}
			else {
				String key = input.type() + ":" + input.campusId() + ":" + input.gradeLevelId() + ":" + input.classId();
				if (seen.add(key)) {
					insertAudience(id, input, null, roles);
				}
				else {
					// the same place twice: merge the roles into the existing row
					this.jdbc.sql("""
							update announcement_audiences set recipient_roles = (select array_agg(distinct x) from unnest(
							         recipient_roles || string_to_array(:roles, ',')) x)
							where announcement_id = :id and audience_type = :type
							  and campus_id is not distinct from :campus and grade_level_id is not distinct from :grade
							  and class_id is not distinct from :class""")
						.param("roles", roles)
						.param("id", id)
						.param("type", input.type().name())
						.param("campus", input.campusId(), java.sql.Types.OTHER)
						.param("grade", input.gradeLevelId(), java.sql.Types.OTHER)
						.param("class", input.classId(), java.sql.Types.OTHER)
						.update();
				}
			}
		}
		if (seen.isEmpty()) {
			throw ApiException.badRequest("announcement.audience_invalid");
		}
	}

	private void insertAudience(UUID id, AnnouncementAudienceInput input, UUID userId, String roles) {
		AudienceType type = input.type();
		this.jdbc.sql("""
				insert into announcement_audiences (announcement_id, audience_type, campus_id, grade_level_id, class_id,
				                                    user_id, recipient_roles)
				values (:id, :type, :campus, :grade, :class, :user, string_to_array(:roles, ','))""")
			.param("id", id)
			.param("type", type.name())
			.param("campus", (type == AudienceType.CAMPUS) ? input.campusId() : null, java.sql.Types.OTHER)
			.param("grade", (type == AudienceType.GRADE_LEVEL) ? input.gradeLevelId() : null, java.sql.Types.OTHER)
			.param("class", (type == AudienceType.CLASS) ? input.classId() : null, java.sql.Types.OTHER)
			.param("user", userId, java.sql.Types.OTHER)
			.param("roles", roles)
			.update();
	}

	/** The audiences as stored, USER rows regrouped into one input. */
	List<AnnouncementAudienceInput> audiencesOf(UUID id) {
		List<AnnouncementAudienceInput> result = new ArrayList<>();
		List<UUID> users = new ArrayList<>();
		Set<RecipientRole> userRoles = new LinkedHashSet<>();
		this.jdbc.sql("""
				select audience_type, campus_id, grade_level_id, class_id, user_id, recipient_roles
				from announcement_audiences where announcement_id = :id order by id""").param("id", id).query((rs) -> {
			AudienceType type = AudienceType.valueOf(rs.getString("audience_type"));
			Set<RecipientRole> roles = new LinkedHashSet<>();
			for (String role : (String[]) rs.getArray("recipient_roles").getArray()) {
				roles.add(RecipientRole.valueOf(role));
			}
			if (type == AudienceType.USER) {
				users.add(rs.getObject("user_id", UUID.class));
				userRoles.addAll(roles);
			}
			else {
				result.add(new AnnouncementAudienceInput(type, rs.getObject("campus_id", UUID.class),
						rs.getObject("grade_level_id", UUID.class), rs.getObject("class_id", UUID.class), List.of(), roles));
			}
		});
		if (!users.isEmpty()) {
			result.add(new AnnouncementAudienceInput(AudienceType.USER, null, null, null, users, userRoles));
		}
		return result;
	}

	/** Audit metadata for an announcement: type and how many audiences of each kind (never text or names). */
	private static Meta audit(AnnouncementType type, List<AnnouncementAudienceInput> audiences) {
		Map<String, Integer> kinds = new TreeMap<>();
		for (AnnouncementAudienceInput a : audiences) {
			kinds.merge(a.type().name(), (a.type() == AudienceType.USER) ? a.userIds().size() : 1, Integer::sum);
		}
		return new Meta().with("type", type.name()).with("audiences", kinds);
	}

	/** Audit metadata under construction (null values are left out). */
	static final class Meta extends LinkedHashMap<String, Object> {

		Meta with(String key, Object value) {
			put(key, value);
			return this;
		}

	}

	/** Writes the audit row of an action on an announcement. */
	private void record(CurrentUser user, String action, UUID id, UUID schoolId, Meta meta) {
		AuditEntry full = AuditEntry.of(action, AuditOutcome.SUCCESS).target("announcement", id).school(schoolId);
		full.metadata(meta);
		if (user.sessionId() == null) {
			// a job acting for the author: record whose announcement it was
			full.actor(user.userId());
		}
		this.audit.record(full);
	}

}
