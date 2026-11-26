package com.nemaz.school.communications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.communications.AnnouncementNoticeRequested;
import com.nemaz.school.communications.AnnouncementPublished;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.NoticeKind;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;
import com.nemaz.school.communications.application.ChannelDispatcher.Target;
import com.nemaz.school.notifications.LocalizedText;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivery of announcements through the notification service, after commit, in chunks of {@value #CHUNK} recipients:
 * a school of thousands never becomes one giant transaction.
 * <ul>
 * <li><b>Publication</b> ({@link #deliver}, on {@link AnnouncementPublished}): each chunk is its own transaction that
 * claims the next recipients with no {@code notified_at} ({@code FOR UPDATE SKIP LOCKED}), creates their in-app
 * notifications ({@code ACK_REQUESTED} when an acknowledgement is asked for, else {@code ANNOUNCEMENT_PUBLISHED}; URGENT
 * for urgent and safety announcements, so push ignores quiet hours and preferences), stamps {@code notified_at} and
 * {@code notification_id}, and plans email and SMS. A crash loses nothing: the chunk rolled back and the next run (the
 * redelivered event or the resume job) continues with the recipients still unnotified. Several workers can run at once.
 * When none is left the announcement is marked {@code delivery_completed_at}, and the due email and SMS are sent.</li>
 * <li><b>Notices</b> ({@link #deliverNotice}, on {@link AnnouncementNoticeRequested}): an update (material edit), a
 * reminder (people who have not acknowledged, evaluated while delivering) or a withdrawal, to the recipients who were
 * notified of the original, keyset by recipient id; people who already have the notice are skipped, so a rerun adds
 * nothing. A notice for an announcement that is withdrawn meanwhile (except the withdrawal itself) is dropped.</li>
 * </ul>
 * Notification texts carry the title (in the recipient's language when a translation exists) and the school's name, never
 * the body.
 */
@Component
public class AnnouncementDelivery {

	static final int CHUNK = 500;

	static final String SOURCE = "ANNOUNCEMENT";

	static final String NOTICE_SOURCE = "ANNOUNCEMENT_NOTICE";

	private static final Logger log = LoggerFactory.getLogger(AnnouncementDelivery.class);

	private final JdbcClient jdbc;

	private final NotificationService notifications;

	private final ChannelDispatcher channels;

	private final AnnouncementRows rows;

	private final Clock clock;

	private final TransactionTemplate tx;

	AnnouncementDelivery(JdbcClient jdbc, NotificationService notifications, ChannelDispatcher channels,
			AnnouncementRows rows, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.channels = channels;
		this.rows = rows;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@ApplicationModuleListener
	void on(AnnouncementPublished event) {
		deliver(event.announcementId());
	}

	@ApplicationModuleListener
	void on(AnnouncementNoticeRequested event) {
		deliverNotice(event.noticeId());
	}

	// --- publication -----------------------------------------------------------------------

	/** Delivers to every recipient still unnotified; returns how many were notified by this call. */
	public int deliver(UUID announcementId) {
		int total = 0;
		while (true) {
			Integer chunk = this.tx.execute((status) -> deliverChunk(announcementId));
			if (chunk == null || chunk == 0) {
				break;
			}
			total += chunk;
		}
		this.tx.executeWithoutResult((status) -> complete(announcementId));
		int sent = this.channels.sendDue(announcementId);
		if (total > 0 || sent > 0) {
			log.info("Announcement {} delivered: {} notified, {} email/SMS attempted", announcementId, total, sent);
		}
		return total;
	}

	private int deliverChunk(UUID announcementId) {
		AnnRow ann = load(announcementId);
		if (ann == null || ann.status() != AnnouncementStatus.PUBLISHED) {
			return 0;
		}
		List<Target> chunk = this.jdbc.sql("""
				select id, user_id from announcement_recipients where announcement_id = :a and notified_at is null
				order by id limit :n for update skip locked""")
			.param("a", announcementId)
			.param("n", CHUNK)
			.query((rs, i) -> new Target(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id")))
			.list();
		if (chunk.isEmpty()) {
			return 0;
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		String type = ann.requiresAcknowledgement() ? "ACK_REQUESTED" : "ANNOUNCEMENT_PUBLISHED";
		NotificationRequest.Builder request = NotificationRequest.to(type, chunk.stream().map(Target::userId).toList())
			.args(titles(ann), schoolName(ann.schoolId()))
			.school(ann.schoolId())
			.source(SOURCE, announcementId)
			.path("/announcements/" + announcementId)
			.data("announcementId", announcementId.toString())
			.data("schoolId", ann.schoolId().toString())
			.data("announcementType", ann.type().name());
		if (ann.type().urgent()) {
			request.urgent();
		}
		this.notifications.notify(request.build());
		this.jdbc.sql("""
				update announcement_recipients r set notified_at = :now,
				       notification_id = (select n.id from notifications n where n.user_id = r.user_id and n.type_code = :type
				                          and n.source_type = :source and n.source_id = r.announcement_id
				                          order by n.created_at desc, n.id desc limit 1)
				where r.id in (:ids)""")
			.param("now", Timestamp.from(now))
			.param("type", type)
			.param("source", SOURCE)
			.param("ids", chunk.stream().map(Target::rowId).toList())
			.update();
		this.channels.plan(ann, chunk, now);
		return chunk.size();
	}

	/** Stamps the announcement complete when nobody is left unnotified. */
	private void complete(UUID announcementId) {
		this.jdbc.sql("""
				update announcements set delivery_completed_at = :now
				where id = :id and delivery_completed_at is null and status in ('PUBLISHED', 'WITHDRAWN')
				  and not exists (select 1 from announcement_recipients where announcement_id = :id and notified_at is null)""")
			.param("now", Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS)))
			.param("id", announcementId)
			.update();
	}

	// --- notices ---------------------------------------------------------------------------

	private record Notice(UUID id, UUID announcementId, NoticeKind kind) {
	}

	/** Delivers a notice to everybody it concerns; returns how many were notified by this call. */
	public int deliverNotice(UUID noticeId) {
		Notice notice = this.jdbc.sql("""
				select id, announcement_id, kind from announcement_notices where id = :id and completed_at is null""")
			.param("id", noticeId)
			.query((rs, i) -> new Notice(Rows.uuid(rs, "id"), Rows.uuid(rs, "announcement_id"),
					NoticeKind.valueOf(rs.getString("kind"))))
			.optional()
			.orElse(null);
		if (notice == null) {
			return 0;
		}
		int total = 0;
		UUID last = null;
		while (true) {
			UUID after = last;
			NoticeChunk chunk = this.tx.execute((status) -> noticeChunk(notice, after));
			if (chunk == null || chunk.scanned() == 0) {
				break;
			}
			total += chunk.notified();
			last = chunk.lastId();
		}
		this.tx.executeWithoutResult((status) -> this.jdbc
			.sql("update announcement_notices set completed_at = :now where id = :id and completed_at is null")
			.param("now", Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS)))
			.param("id", noticeId)
			.update());
		return total;
	}

	private record NoticeChunk(int scanned, int notified, UUID lastId) {
	}

	private NoticeChunk noticeChunk(Notice notice, UUID after) {
		AnnRow ann = load(notice.announcementId());
		if (ann == null) {
			return new NoticeChunk(0, 0, null);
		}
		boolean withdrawn = ann.status() == AnnouncementStatus.WITHDRAWN;
		if ((notice.kind() != NoticeKind.WITHDRAWN && withdrawn) || ann.status() == AnnouncementStatus.DRAFT) {
			return new NoticeChunk(0, 0, null);
		}
		String reminderFilter = (notice.kind() == NoticeKind.REMINDER) ? """
				 and not exists (select 1 from announcement_acknowledgements ak where ak.announcement_id = r.announcement_id
				                 and ak.user_id = r.user_id and ak.acknowledged_at is not null)""" : "";
		List<Target> scanned = this.jdbc.sql("""
				select r.id, r.user_id from announcement_recipients r
				where r.announcement_id = :a and r.notified_at is not null and (cast(:after as uuid) is null or r.id > cast(:after as uuid))"""
				+ reminderFilter + " order by r.id limit :n")
			.param("a", ann.id())
			.param("after", after, java.sql.Types.OTHER)
			.param("n", CHUNK)
			.query((rs, i) -> new Target(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id")))
			.list();
		if (scanned.isEmpty()) {
			return new NoticeChunk(0, 0, null);
		}
		String type = switch (notice.kind()) {
			case UPDATED -> "ANNOUNCEMENT_UPDATED";
			case REMINDER -> "ANNOUNCEMENT_REMINDER";
			case WITHDRAWN -> "ANNOUNCEMENT_WITHDRAWN";
		};
		Set<UUID> already = Set.copyOf(this.jdbc.sql("""
				select user_id from notifications where type_code = :type and source_type = :source and source_id = :notice
				  and user_id in (:users)""")
			.param("type", type)
			.param("source", NOTICE_SOURCE)
			.param("notice", notice.id())
			.param("users", scanned.stream().map(Target::userId).toList())
			.query(UUID.class)
			.list());
		List<UUID> todo = scanned.stream().map(Target::userId).filter((u) -> !already.contains(u)).collect(Collectors.toList());
		if (!todo.isEmpty()) {
			NotificationRequest.Builder request = NotificationRequest.to(type, todo)
				.args(titles(ann), schoolName(ann.schoolId()))
				.school(ann.schoolId())
				.source(NOTICE_SOURCE, notice.id())
				.path("/announcements/" + ann.id())
				.data("announcementId", ann.id().toString())
				.data("schoolId", ann.schoolId().toString())
				.data("announcementType", ann.type().name());
			if (ann.type().urgent() && notice.kind() != NoticeKind.WITHDRAWN) {
				request.urgent();
			}
			this.notifications.notify(request.build());
		}
		return new NoticeChunk(scanned.size(), todo.size(), scanned.getLast().rowId());
	}

	// --- shared ----------------------------------------------------------------------------

	private AnnRow load(UUID id) {
		return this.jdbc.sql("select " + AnnouncementRows.COLUMNS + " from announcements an where an.id = :id")
			.param("id", id)
			.query((rs, i) -> this.rows.map(rs))
			.optional()
			.orElse(null);
	}

	private String schoolName(UUID schoolId) {
		return this.jdbc.sql("select name from schools where id = :id").param("id", schoolId).query(String.class).single();
	}

	/** The title in each language it exists in; the recipient gets theirs, else English, else the original. */
	static LocalizedText titles(AnnRow ann) {
		Map<String, String> titles = new LinkedHashMap<>();
		titles.put((ann.languageCode() != null) ? ann.languageCode() : "und", ann.title());
		ann.translations().forEach((lang, t) -> titles.putIfAbsent(lang, t.title()));
		return LocalizedText.of(titles);
	}

	// --- resume ----------------------------------------------------------------------------

	/** Ids of published announcements with unnotified recipients older than {@code before} (interrupted deliveries). */
	List<UUID> undelivered(Instant before, int limit) {
		return this.jdbc.sql("""
				select id from announcements where status = 'PUBLISHED' and delivery_completed_at is null
				  and published_at < :before order by published_at, id limit :n""")
			.param("before", Timestamp.from(before))
			.param("n", limit)
			.query(UUID.class)
			.list();
	}

	/** Open notices requested before {@code before}. */
	List<UUID> openNotices(Instant before, int limit) {
		return this.jdbc.sql("""
				select id from announcement_notices where completed_at is null and requested_at < :before
				order by requested_at, id limit :n""")
			.param("before", Timestamp.from(before))
			.param("n", limit)
			.query(UUID.class)
			.list();
	}

}
