package com.nemaz.school.communications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.communications.application.AnnouncementAccess.Pred;
import com.nemaz.school.communications.application.AnnouncementAccess.ViewerProfile;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementCapabilities;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementCard;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementClassRef;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementRevisionView;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementStatus;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementType;
import com.nemaz.school.communications.application.AnnouncementModels.AnnouncementUnreadSummary;
import com.nemaz.school.communications.application.AnnouncementModels.InboxFilter;
import com.nemaz.school.communications.application.AnnouncementModels.InboxSort;
import com.nemaz.school.communications.application.AnnouncementRows.AnnRow;
import com.nemaz.school.identity.UserDirectory;
import com.nemaz.school.identity.UserDirectory.UserProfileView;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lists: what the caller wrote ({@link #authored}), what is addressed to them across schools ({@link #inbox}: the
 * recipient snapshot), what they may read in one school ({@link #schoolList}: snapshot, author, managers and late
 * joiners), the unread badge, the revisions and what the caller may compose. Every list is filtered in SQL by
 * {@link AnnouncementAccess#readable} (no Java-side filtering), keyset paged, 404 for a school the caller cannot see.
 */
@Service
@Transactional(readOnly = true)
public class AnnouncementQueries {

	private final JdbcClient jdbc;

	private final AnnouncementAccess access;

	private final AnnouncementViews views;

	private final CommunicationsSettings settings;

	private final UserDirectory users;

	private final Clock clock;

	AnnouncementQueries(JdbcClient jdbc, AnnouncementAccess access, AnnouncementViews views,
			CommunicationsSettings settings, UserDirectory users, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.views = views;
		this.settings = settings;
		this.users = users;
		this.clock = clock;
	}

	/** The announcements the caller may manage: every one of the school for managers, their own for everybody else. */
	public CursorPage<AnnouncementCard> authored(CurrentUser user, UUID schoolId, AnnouncementStatus status, boolean mineOnly,
			String cursor, Integer limit) {
		this.access.requireWrite(user, schoolId);
		boolean manager = this.access.manager(user, schoolId) && !mineOnly;
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("me", user.userId());
		params.put("school", schoolId);
		StringBuilder where = new StringBuilder("an.deleted_at is null and an.school_id = :school");
		if (!manager) {
			where.append(" and an.author_id = :me");
		}
		if (status != null) {
			where.append(" and an.status = :status");
			params.put("status", status.name());
		}
		int pageSize = Cursors.clampLimit(limit);
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			where.append(" and (an.created_at, an.id) < (:cursor_at, :cursor_id)");
			params.put("cursor_at", Timestamp.from(instant(parts.get(0))));
			params.put("cursor_id", uuid(parts.get(1)));
		}
		List<AnnouncementCard> rows = cards(where.toString(), "an.created_at desc, an.id desc", params, pageSize + 1, true);
		return CursorPage.fromOverfetch(rows, pageSize, (c) -> Cursors.encode(c.createdAt().toString(), c.id().toString()));
	}

	/** What the caller may read in the school (published and withdrawn), newest first. */
	public CursorPage<AnnouncementCard> schoolList(CurrentUser user, UUID schoolId, InboxFilter filter, InboxSort sort,
			String cursor, Integer limit) {
		ViewerProfile profile = this.access.profile(user);
		this.access.requireRead(user, schoolId, profile);
		Pred pred = this.access.readable(profile, false, this.access.manager(user, schoolId), this.clock.instant());
		return live(user, pred, schoolId, filter, sort, cursor, limit);
	}

	/** What was delivered to the caller (the recipient snapshot), across schools, optionally one school. */
	public CursorPage<AnnouncementCard> inbox(CurrentUser user, UUID schoolId, InboxFilter filter, InboxSort sort,
			String cursor, Integer limit) {
		ViewerProfile profile = this.access.profile(user);
		Pred pred = this.access.readable(profile, true, false, this.clock.instant());
		return live(user, pred, schoolId, filter, sort, cursor, limit);
	}

	private CursorPage<AnnouncementCard> live(CurrentUser user, Pred pred, UUID schoolId, InboxFilter filter, InboxSort sort,
			String cursor, Integer limit) {
		Map<String, Object> params = new LinkedHashMap<>(pred.params());
		params.put("me", user.userId());
		StringBuilder where = new StringBuilder("an.deleted_at is null and an.status in ('PUBLISHED', 'WITHDRAWN') and ")
			.append(pred.sql());
		if (schoolId != null) {
			where.append(" and an.school_id = :school");
			params.put("school", schoolId);
		}
		InboxFilter f = (filter != null) ? filter : InboxFilter.ALL;
		if (f == InboxFilter.UNREAD) {
			where.append(" and ak.first_read_at is null");
		}
		else if (f == InboxFilter.NEEDS_ACKNOWLEDGEMENT) {
			where.append(" and an.requires_acknowledgement and an.status = 'PUBLISHED' and ak.acknowledged_at is null");
		}
		boolean unreadFirst = sort == InboxSort.UNREAD_FIRST;
		int pageSize = Cursors.clampLimit(limit);
		if (cursor != null) {
			if (unreadFirst) {
				List<String> parts = Cursors.decode(cursor, 3);
				where.append(" and (" + UNREAD_FLAG + ", an.published_at, an.id) < (:cursor_u, :cursor_at, :cursor_id)");
				params.put("cursor_u", "1".equals(parts.get(0)) ? 1 : 0);
				params.put("cursor_at", Timestamp.from(instant(parts.get(1))));
				params.put("cursor_id", uuid(parts.get(2)));
			}
			else {
				List<String> parts = Cursors.decode(cursor, 2);
				where.append(" and (an.published_at, an.id) < (:cursor_at, :cursor_id)");
				params.put("cursor_at", Timestamp.from(instant(parts.get(0))));
				params.put("cursor_id", uuid(parts.get(1)));
			}
		}
		String order = unreadFirst ? UNREAD_FLAG + " desc, an.published_at desc, an.id desc" : "an.published_at desc, an.id desc";
		List<AnnouncementCard> rows = cards(where.toString(), order, params, pageSize + 1, false);
		return CursorPage.fromOverfetch(rows, pageSize, (c) -> unreadFirst
				? Cursors.encode((c.readAt() == null) ? "1" : "0", c.publishedAt().toString(), c.id().toString())
				: Cursors.encode(c.publishedAt().toString(), c.id().toString()));
	}

	private static final String UNREAD_FLAG = "(case when ak.first_read_at is null then 1 else 0 end)";

	/** How many announcements delivered to the caller are unread and how many still wait for their acknowledgement. */
	public AnnouncementUnreadSummary unread(CurrentUser user) {
		ViewerProfile profile = this.access.profile(user);
		Pred pred = this.access.readable(profile, true, false, this.clock.instant());
		return this.jdbc.sql("""
				select count(*) filter (where ak.first_read_at is null) as unread,
				       count(*) filter (where an.requires_acknowledgement and ak.acknowledged_at is null) as needs_ack
				from announcements an
				left join announcement_acknowledgements ak on ak.announcement_id = an.id and ak.user_id = :me
				where an.deleted_at is null and an.status = 'PUBLISHED' and\s""" + pred.sql())
			.params(pred.params())
			.param("me", user.userId())
			.query((rs, i) -> new AnnouncementUnreadSummary(rs.getInt("unread"), rs.getInt("needs_ack")))
			.single();
	}

	private List<AnnouncementCard> cards(String where, String order, Map<String, Object> params, int limit, boolean authorView) {
		String sql = """
				select an.id, an.school_id, s.name as school_name, an.author_id, u.display_name as author_name,
				       an.announcement_type, an.status, an.title,
				       case when an.status = 'WITHDRAWN' and an.author_id <> :me and not :manager_view then null
				            else left(an.body, 200) end as excerpt,
				       an.requires_acknowledgement, an.scheduled_for, an.published_at, an.withdrawn_at, an.revision,
				       an.created_at, ak.first_read_at, ak.acknowledged_at,
				       case when an.author_id = :me or :manager_view then an.recipient_count end as recipient_count
				from announcements an
				join schools s on s.id = an.school_id
				join users u on u.id = an.author_id
				left join announcement_acknowledgements ak on ak.announcement_id = an.id and ak.user_id = :me
				where\s""" + where + " order by " + order + " limit :n";
		return this.jdbc.sql(sql)
			.params(params)
			.param("manager_view", authorView)
			.param("n", limit)
			.query((rs, i) -> new AnnouncementCard(Rows.uuid(rs, "id"), Rows.uuid(rs, "school_id"), rs.getString("school_name"),
					Rows.uuid(rs, "author_id"), rs.getString("author_name"),
					AnnouncementType.valueOf(rs.getString("announcement_type")),
					AnnouncementStatus.valueOf(rs.getString("status")), rs.getString("title"),
					(rs.getString("excerpt") != null) ? BodyText.oneLine(rs.getString("excerpt"), 160) : null,
					rs.getBoolean("requires_acknowledgement"), Rows.instant(rs, "scheduled_for"),
					Rows.instant(rs, "published_at"), Rows.instant(rs, "withdrawn_at"), rs.getInt("revision"),
					Rows.instant(rs, "created_at"), Rows.instant(rs, "first_read_at"), Rows.instant(rs, "acknowledged_at"),
					Rows.integer(rs, "recipient_count")))
			.list();
	}

	/** Previous versions of a published announcement, newest first (author and managers). */
	public List<AnnouncementRevisionView> revisions(CurrentUser user, UUID schoolId, UUID id) {
		this.access.requireWrite(user, schoolId);
		AnnRow row = this.views.load(schoolId, id);
		this.views.requireMayManage(user, row);
		List<AnnouncementRevisionView> list = this.jdbc.sql("""
				select revision, title, body, translations::text as translations, material, change_note, edited_by, edited_at
				from announcement_revisions where announcement_id = :id order by revision desc""")
			.param("id", id)
			.query((rs, i) -> new AnnouncementRevisionView(rs.getInt("revision"), rs.getString("title"),
					rs.getString("body"), this.views.translationsOf(rs.getString("translations")),
					rs.getBoolean("material"), rs.getString("change_note"), Rows.uuid(rs, "edited_by"), null,
					Rows.instant(rs, "edited_at")))
			.list();
		Map<UUID, UserProfileView> names = this.users
			.profiles(list.stream().map(AnnouncementRevisionView::editedBy).distinct().toList());
		return list.stream()
			.map((r) -> new AnnouncementRevisionView(r.revision(), r.title(), r.body(), r.translations(), r.material(),
					r.changeNote(), r.editedBy(),
					names.containsKey(r.editedBy()) ? names.get(r.editedBy()).displayName() : null, r.editedAt()))
			.toList();
	}

	/** What the caller may compose in the school (for menus; the API stays the authority). */
	public AnnouncementCapabilities capabilities(CurrentUser user, UUID schoolId) {
		this.access.requireWrite(user, schoolId);
		boolean manager = this.access.manager(user, schoolId);
		boolean teachers = this.settings.teachersCanAnnounce(schoolId);
		List<AnnouncementClassRef> classRefs = new ArrayList<>();
		if (!manager) {
			Set<UUID> ids = this.access.announceClasses(user, schoolId, teachers);
			if (!ids.isEmpty()) {
				classRefs = this.jdbc.sql("select id, name from classes where id in (:ids) order by name, id")
					.param("ids", ids)
					.query((rs, i) -> new AnnouncementClassRef(Rows.uuid(rs, "id"), rs.getString("name")))
					.list();
			}
		}
		boolean canCompose = manager || !classRefs.isEmpty();
		boolean sms = this.settings.smsEnabled(schoolId);
		return new AnnouncementCapabilities(canCompose, manager, this.access.urgentAllowed(user, schoolId),
				this.settings.studentsEnabled(schoolId), this.settings.emailAvailable(schoolId), sms,
				sms ? this.settings.smsTypes(schoolId).stream().sorted().toList() : List.of(), classRefs,
				this.settings.reminderMinHours(schoolId), timeZoneOf(schoolId), this.settings.smsPerAnnouncement(schoolId),
				this.settings.smsPerDay(schoolId));
	}

	/** The school's time zone, the one scheduled announcements are read in (UTC when unknown). */
	private String timeZoneOf(UUID schoolId) {
		String name = this.jdbc.sql("select timezone from schools where id = :school")
			.param("school", schoolId)
			.query(String.class)
			.optional()
			.orElse("UTC");
		try {
			return java.time.ZoneId.of(name).getId();
		}
		catch (java.time.DateTimeException ex) {
			return "UTC";
		}
	}

	private static Instant instant(String text) {
		try {
			return Instant.parse(text);
		}
		catch (java.time.format.DateTimeParseException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

	private static UUID uuid(String text) {
		try {
			return UUID.fromString(text);
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

}
