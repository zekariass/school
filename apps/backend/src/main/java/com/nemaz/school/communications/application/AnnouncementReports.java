package com.nemaz.school.communications.application;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.text.CsvCells;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementRecipientView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementReminderView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementReportSummary;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.ChannelCounts;
import com.nemaz.school.communications.application.AnnouncementModels.NoticeKind;
import com.nemaz.school.communications.application.AnnouncementModels.RecipientFilter;
import com.nemaz.school.communications.application.AnnouncementModels.RecipientRole;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserProfileView;
import com.nemaz.school.system.RateLimiter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery report of an announcement for its author and the school's announcement managers (a reader gets 403, anybody
 * else 404). Delivery, reading and acknowledgement are counted apart, over the recipient snapshot:
 * <ul>
 * <li><b>delivered</b>: recipients whose in-app notification exists ({@code notified_at}); <b>failed</b>: recipients with at
 * least one failed channel (push the provider refused, email or SMS that failed); the per-channel counts say what was sent,
 * failed, skipped (not wanted: opted out, no contact or device, over a cap, switched off) or is still pending (held for quiet
 * hours or not yet delivered).</li>
 * <li><b>read</b> and <b>acknowledged</b>: from {@code announcement_acknowledgements}, independent of delivery; people
 * outside the snapshot who opened it are counted as {@code lateReaders} and never in the other numbers.</li>
 * </ul>
 * The recipient list shows names (the author and managers may see these people already: the audience is limited to what
 * they may see), masked phone and email only, and the child through whom a guardian was reached. Listing it is audited
 * without names; the CSV is audited, rate limited (10 per 10 minutes) and capped at {@value #MAX_EXPORT_ROWS} rows. Also
 * here: the reminder to people who have not acknowledged.
 */
@Service
public class AnnouncementReports {

	static final int MAX_EXPORT_ROWS = 20000;

	static final int MAX_REMINDERS = 5;

	private static final String PUSH_STATUS = """
			left join lateral (
			  select case
			           when bool_or(d.status in ('SENT', 'DELIVERED')) then 'SENT'
			           when bool_or(d.status = 'QUEUED' and d.not_before is not null) then 'HELD'
			           when bool_or(d.status = 'QUEUED') then 'PENDING'
			           when bool_or(d.status = 'FAILED') then 'FAILED'
			           when count(d.id) > 0 then 'SUPPRESSED'
			           else 'NONE' end as push_status
			  from notification_deliveries d where d.notification_id = r.notification_id) pd on true""";

	private final JdbcClient jdbc;

	private final AnnouncementAccess access;

	private final AnnouncementViews views;

	private final AnnouncementService service;

	private final CommunicationsSettings settings;

	private final UserDirectory users;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final MessageSource messages;

	private final Clock clock;

	AnnouncementReports(JdbcClient jdbc, AnnouncementAccess access, AnnouncementViews views, AnnouncementService service,
			CommunicationsSettings settings, UserDirectory users, RateLimiter limiter, AuditLog audit, MessageSource messages,
			Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.views = views;
		this.service = service;
		this.settings = settings;
		this.users = users;
		this.limiter = limiter;
		this.audit = audit;
		this.messages = messages;
		this.clock = clock;
	}

	// --- summary ---------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public AnnouncementReportSummary summary(CurrentUser user, UUID schoolId, UUID id) {
		AnnRow row = reportable(user, schoolId, id);
		record Counts(int recipients, int delivered, int failed, int read, int acknowledged, ChannelCounts push,
				ChannelCounts email, ChannelCounts sms) {
		}
		Counts c = this.jdbc.sql("""
				select count(*) as recipients,
				  count(*) filter (where r.notified_at is not null) as delivered,
				  count(*) filter (where pd.push_status = 'FAILED' or r.email_status = 'FAILED' or r.sms_status = 'FAILED') as failed,
				  count(*) filter (where ak.first_read_at is not null) as read,
				  count(*) filter (where ak.acknowledged_at is not null) as acknowledged,
				  count(*) filter (where pd.push_status = 'SENT') as push_sent,
				  count(*) filter (where pd.push_status = 'FAILED') as push_failed,
				  count(*) filter (where r.notified_at is null or pd.push_status in ('PENDING', 'HELD')) as push_pending,
				  count(*) filter (where r.notified_at is not null and pd.push_status in ('SUPPRESSED', 'NONE')) as push_skipped,
				  count(*) filter (where r.email_status = 'SENT') as email_sent,
				  count(*) filter (where r.email_status = 'FAILED') as email_failed,
				  count(*) filter (where r.email_status in ('PENDING', 'SENDING')) as email_pending,
				  count(*) filter (where r.email_status = 'SKIPPED') as email_skipped,
				  count(*) filter (where r.sms_status = 'SENT') as sms_sent,
				  count(*) filter (where r.sms_status = 'FAILED') as sms_failed,
				  count(*) filter (where r.sms_status in ('PENDING', 'SENDING')) as sms_pending,
				  count(*) filter (where r.sms_status = 'SKIPPED') as sms_skipped
				from announcement_recipients r
				left join announcement_acknowledgements ak on ak.announcement_id = r.announcement_id and ak.user_id = r.user_id
				""" + PUSH_STATUS + " where r.announcement_id = :id")
			.param("id", id)
			.query((rs, i) -> new Counts(rs.getInt("recipients"), rs.getInt("delivered"), rs.getInt("failed"),
					rs.getInt("read"), rs.getInt("acknowledged"),
					new ChannelCounts(rs.getInt("push_sent"), rs.getInt("push_failed"), rs.getInt("push_skipped"),
							rs.getInt("push_pending")),
					new ChannelCounts(rs.getInt("email_sent"), rs.getInt("email_failed"), rs.getInt("email_skipped"),
							rs.getInt("email_pending")),
					new ChannelCounts(rs.getInt("sms_sent"), rs.getInt("sms_failed"), rs.getInt("sms_skipped"),
							rs.getInt("sms_pending"))))
			.single();
		int late = this.jdbc.sql("""
				select count(*) from announcement_acknowledgements ak
				where ak.announcement_id = :id and ak.first_read_at is not null
				  and not exists (select 1 from announcement_recipients r where r.announcement_id = ak.announcement_id
				                  and r.user_id = ak.user_id)""").param("id", id).query(Integer.class).single();
		record Reminders(int count, Instant last) {
		}
		Reminders reminders = this.jdbc.sql("""
				select count(*) as n, max(requested_at) as last from announcement_notices
				where announcement_id = :id and kind = 'REMINDER'""")
			.param("id", id)
			.query((rs, i) -> new Reminders(rs.getInt("n"), Rows.instant(rs, "last")))
			.single();
		return new AnnouncementReportSummary(id, row.status(), c.recipients(), c.delivered(), c.failed(), c.read(),
				c.acknowledged(), row.requiresAcknowledgement(), row.deliveryCompletedAt() != null, c.push(), c.email(),
				c.sms(), late, reminders.last(), reminders.count());
	}

	// --- the recipient list ----------------------------------------------------------------

	@Transactional
	public CursorPage<AnnouncementRecipientView> recipients(CurrentUser user, UUID schoolId, UUID id, RecipientFilter filter,
			String cursor, Integer limit) {
		reportable(user, schoolId, id);
		int pageSize = Cursors.clampLimit(limit);
		UUID after = null;
		if (cursor != null) {
			try {
				after = UUID.fromString(Cursors.decode(cursor, 1).getFirst());
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<RecipientRow> rows = query(id, filter, after, pageSize + 1, false);
		List<AnnouncementRecipientView> views = toViews(rows.subList(0, Math.min(rows.size(), pageSize)));
		this.audit.record(AuditEntry.of("announcement.report.read", AuditOutcome.SUCCESS)
			.target("announcement", id)
			.school(schoolId)
			.metadata("filter", (filter != null) ? filter.name() : "ALL")
			.metadata("rows", views.size()));
		String next = (rows.size() > pageSize) ? Cursors.encode(rows.get(pageSize - 1).rowId().toString()) : null;
		return new CursorPage<>(views, next);
	}

	private record RecipientRow(UUID rowId, UUID userId, RecipientRole role, UUID viaStudentId, Instant notifiedAt,
			String push, String email, String sms, Instant readAt, Instant acknowledgedAt, Integer revision) {
	}

	private List<RecipientRow> query(UUID id, RecipientFilter filter, UUID after, int limit, boolean byName) {
		StringBuilder where = new StringBuilder(" where r.announcement_id = :id");
		if (after != null) {
			where.append(" and r.id > :after");
		}
		RecipientFilter f = (filter != null) ? filter : RecipientFilter.ALL;
		switch (f) {
			case UNREAD -> where.append(" and ak.first_read_at is null");
			case UNACKNOWLEDGED -> where.append(" and ak.acknowledged_at is null");
			case ACKNOWLEDGED -> where.append(" and ak.acknowledged_at is not null");
			case FAILED -> where.append(
					" and (pd.push_status = 'FAILED' or r.email_status = 'FAILED' or r.sms_status = 'FAILED')");
			case ALL -> {
			}
		}
		String order = byName ? " order by u.display_name, r.id" : " order by r.id";
		return this.jdbc.sql("""
				select r.id, r.user_id, r.recipient_role, r.via_student_id, r.notified_at, pd.push_status,
				       r.email_status, r.email_reason, r.sms_status, r.sms_reason, ak.first_read_at, ak.acknowledged_at,
				       ak.acknowledged_revision
				from announcement_recipients r
				join users u on u.id = r.user_id
				left join announcement_acknowledgements ak on ak.announcement_id = r.announcement_id and ak.user_id = r.user_id
				""" + PUSH_STATUS + where + order + " limit :n")
			.param("id", id)
			.param("after", after, java.sql.Types.OTHER)
			.param("n", limit)
			.query((rs, i) -> new RecipientRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id"),
					RecipientRole.valueOf(rs.getString("recipient_role")), Rows.uuid(rs, "via_student_id"),
					Rows.instant(rs, "notified_at"), (rs.getString("push_status") != null) ? rs.getString("push_status") : "NONE",
					channel(rs.getString("email_status"), rs.getString("email_reason")),
					channel(rs.getString("sms_status"), rs.getString("sms_reason")), Rows.instant(rs, "first_read_at"),
					Rows.instant(rs, "acknowledged_at"), Rows.integer(rs, "acknowledged_revision")))
			.list();
	}

	private static String channel(String status, String reason) {
		if (status == null) {
			return "NOT_REQUESTED";
		}
		return ("SKIPPED".equals(status) && reason != null) ? "SKIPPED_" + reason : status;
	}

	private List<AnnouncementRecipientView> toViews(List<RecipientRow> rows) {
		Set<UUID> ids = new HashSet<>();
		rows.forEach((r) -> {
			ids.add(r.userId());
			if (r.viaStudentId() != null) {
				ids.add(r.viaStudentId());
			}
		});
		Map<UUID, UserProfileView> profiles = this.users.profiles(ids);
		return rows.stream().map((r) -> {
			UserProfileView person = profiles.get(r.userId());
			UserProfileView child = (r.viaStudentId() != null) ? profiles.get(r.viaStudentId()) : null;
			return new AnnouncementRecipientView(r.userId(), (person != null) ? person.displayName() : "", r.role(),
					r.viaStudentId(), (child != null) ? child.displayName() : null,
					(person != null) ? person.maskedPhone() : null, (person != null) ? person.maskedEmail() : null,
					r.notifiedAt(), r.push(), r.email(), r.sms(), r.readAt(), r.acknowledgedAt(), r.revision());
		}).toList();
	}

	// --- CSV -------------------------------------------------------------------------------

	/** The CSV file of a report. */
	public record ExportFile(byte[] content, String filename) {
	}

	/**
	 * Every recipient, by name, as a CSV file: UTF-8 with a byte order mark, CRLF line ends, headers in the caller's language,
	 * spreadsheet formulas neutralized, no contact details. Audited, 10 per 10 minutes per user (429
	 * {@code announcement.export_rate_limited}), at most {@value #MAX_EXPORT_ROWS} rows (422 {@code announcement.export_too_large}).
	 */
	@Transactional
	public ExportFile export(CurrentUser user, UUID schoolId, UUID id) {
		reportable(user, schoolId, id);
		this.limiter.consume("announcement-export", user.userId().toString(), 10, Duration.ofMinutes(10),
				"announcement.export_rate_limited");
		List<RecipientRow> rows = query(id, RecipientFilter.ALL, null, MAX_EXPORT_ROWS + 1, true);
		if (rows.size() > MAX_EXPORT_ROWS) {
			throw ApiException.unprocessable("announcement.export_too_large");
		}
		Map<UUID, UserProfileView> profiles = this.users.profiles(rows.stream().map(RecipientRow::userId).distinct().toList());
		Locale locale = LocaleContextHolder.getLocale();
		StringWriter out = new StringWriter();
		out.write('﻿');
		try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
			printer.printRecord(text("name", locale), text("role", locale), text("delivered_at", locale),
					text("push", locale), text("email", locale), text("sms", locale), text("read_at", locale),
					text("acknowledged_at", locale), text("acknowledged_revision", locale));
			for (RecipientRow r : rows) {
				UserProfileView person = profiles.get(r.userId());
				printer.printRecord(CsvCells.safe((person != null) ? person.displayName() : ""), r.role().name(),
						iso(r.notifiedAt()), r.push(), r.email(), r.sms(), iso(r.readAt()), iso(r.acknowledgedAt()),
						(r.revision() != null) ? r.revision().toString() : "");
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.audit.record(AuditEntry.of("announcement.report.export", AuditOutcome.SUCCESS)
			.target("announcement", id)
			.school(schoolId)
			.metadata("rows", rows.size()));
		return new ExportFile(out.toString().getBytes(StandardCharsets.UTF_8), "announcement-report.csv");
	}

	private static String iso(Instant instant) {
		return (instant != null) ? instant.toString() : "";
	}

	private String text(String column, Locale locale) {
		return this.messages.getMessage("announcement.report.csv." + column, null, locale);
	}

	// --- reminders -------------------------------------------------------------------------

	/**
	 * Reminds the recipients who have not acknowledged (in-app and push, urgent for urgent announcements, never SMS or
	 * email). At most once every {@code announcements.reminder.min_hours} (429 {@code announcement.reminder_too_soon} with
	 * Retry-After), at most {@value #MAX_REMINDERS} times per announcement, 10 requests per user per hour. Who is reminded is
	 * decided while delivering: somebody who acknowledges meanwhile is skipped.
	 */
	@Transactional
	public AnnouncementReminderView remind(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = this.service.lock(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() != AnnouncementStatus.PUBLISHED) {
			throw ApiException.conflict("announcement.invalid_state");
		}
		if (!row.requiresAcknowledgement()) {
			throw ApiException.conflict("announcement.ack_not_required");
		}
		this.limiter.consume("announcement-remind", user.userId().toString(), 10, Duration.ofHours(1),
				"announcement.rate_limited");
		Instant now = this.clock.instant();
		record Last(int count, Instant at) {
		}
		Last last = this.jdbc.sql("""
				select count(*) as n, max(requested_at) as at from announcement_notices
				where announcement_id = :id and kind = 'REMINDER'""")
			.param("id", id)
			.query((rs, i) -> new Last(rs.getInt("n"), Rows.instant(rs, "at")))
			.single();
		if (last.count() >= MAX_REMINDERS) {
			throw ApiException.conflict("announcement.reminder_limit_reached");
		}
		Duration gap = Duration.ofHours(this.settings.reminderMinHours(schoolId));
		if (last.at() != null && last.at().plus(gap).isAfter(now)) {
			throw ApiException.tooManyRequests("announcement.reminder_too_soon",
					Math.max(1, Duration.between(now, last.at().plus(gap)).toSeconds()));
		}
		int targeted = this.jdbc.sql("""
				select count(*) from announcement_recipients r
				where r.announcement_id = :id and r.notified_at is not null and not exists (
				  select 1 from announcement_acknowledgements ak where ak.announcement_id = r.announcement_id
				    and ak.user_id = r.user_id and ak.acknowledged_at is not null)""")
			.param("id", id)
			.query(Integer.class)
			.single();
		if (targeted == 0) {
			throw ApiException.conflict("announcement.nobody_to_remind");
		}
		UUID notice = this.service.requestNotice(user, row, NoticeKind.REMINDER, row.revision(), targeted);
		this.audit.record(AuditEntry.of("announcement.remind", AuditOutcome.SUCCESS)
			.target("announcement", id)
			.school(schoolId)
			.metadata("targeted", targeted)
			.metadata("notice", notice.toString())
			.metadata("reminder", last.count() + 1));
		return new AnnouncementReminderView(notice, targeted, now);
	}

	// --- shared ----------------------------------------------------------------------------

	/** The announcement for its author and managers; the report exists once it was published (or withdrawn). */
	private AnnRow reportable(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = this.views.load(schoolId, id);
		this.views.requireMayManage(user, row);
		if (row.status() == AnnouncementStatus.DRAFT || row.status() == AnnouncementStatus.SCHEDULED) {
			throw ApiException.conflict("announcement.not_published");
		}
		return row;
	}

}
