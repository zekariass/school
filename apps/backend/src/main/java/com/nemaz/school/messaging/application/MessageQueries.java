package com.nemaz.school.messaging.application;

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
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.messaging.application.MessagingModels.AttachmentView;
import com.nemaz.school.messaging.application.MessagingModels.ContextStudentView;
import com.nemaz.school.messaging.application.MessagingModels.ConversationSummaryView;
import com.nemaz.school.messaging.application.MessagingModels.ConversationView;
import com.nemaz.school.messaging.application.MessagingModels.LastMessageView;
import com.nemaz.school.messaging.application.MessagingModels.MemberListView;
import com.nemaz.school.messaging.application.MessagingModels.MessageItemView;
import com.nemaz.school.messaging.application.MessagingModels.ParticipantView;
import com.nemaz.school.messaging.application.MessagingModels.ReactionView;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.MemberRole;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.messaging.infrastructure.MessagingRows.MemberRow;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every read of messaging (rule book R10, R12): the inbox, one conversation, its members, its messages and unread counts.
 * Membership <em>and</em> the live eligibility of {@link Eligibility} are part of every SQL statement here; the viewer's own
 * blocks hide the messages of the people they blocked (never for an observer, whose view the safeguarding floor protects); the
 * body of a deleted or hidden message never leaves the database (a tombstone is returned).
 */
@Component
@Transactional(readOnly = true)
public class MessageQueries {

	private static final int PREVIEW_CHARS = 120;

	private static final String VISIBLE_STATUSES = "('SENT', 'EDITED', 'DELETED', 'HIDDEN')";

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final Eligibility eligibility;

	private final ParticipantViews participants;

	private final MediaService media;

	private final MessagingSettings settings;

	private final Clock clock;

	MessageQueries(JdbcClient jdbc, MessagingPolicy policy, Eligibility eligibility, ParticipantViews participants,
			MediaService media, MessagingSettings settings, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.eligibility = eligibility;
		this.participants = participants;
		this.media = media;
		this.settings = settings;
		this.clock = clock;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// inbox

	public CursorPage<ConversationSummaryView> inbox(CurrentUser user, UUID schoolFilter, UUID studentFilter, String cursor,
			Integer limit) {
		int size = Cursors.clampLimit(limit);
		Instant cursorAt = null;
		UUID cursorId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				cursorAt = Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS);
				cursorId = UUID.fromString(parts.get(1));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<UUID> schools = this.eligibility.schoolsOf(user.userId());
		if (schoolFilter != null) {
			schools = schools.stream().filter(schoolFilter::equals).toList();
		}
		if (schools.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		List<InboxRow> rows = this.eligibility.statement(schools, """
				select %s, %s,
				       %s as unread,
				       lm.id as lm_id, lm.message_type as lm_type, lm.status as lm_status, lm.sender_member_id as lm_sender,
				       lm.sent_at as lm_sent,
				       case when lm.status in ('SENT', 'EDITED') and lm.message_type = 'TEXT' then left(lm.body, %d) end as lm_preview,
				       (select o.id from conversation_members o where o.conversation_id = c.id and o.left_at is null
				                 and o.member_role <> 'OBSERVER' and o.user_id <> m.user_id limit 1) as other_member,
				       coalesce(m.on_behalf_of_student_id, c.context_student_id) as ctx_id,
				       (select x.display_name from users x where x.id = coalesce(m.on_behalf_of_student_id, c.context_student_id)) as ctx_name,
				       case when c.safeguarded then exists (select 1 from conversation_members ob
				            where ob.conversation_id = c.id and ob.member_role = 'OBSERVER' and %s) end as guardians_can_see
				from conversation_members m
				join conversations c on c.id = m.conversation_id
				join sp on sp.school_id = c.school_id
				left join lateral (select l.id, l.message_type, l.status, l.sender_member_id, l.sent_at, l.body from messages l
				                   where l.conversation_id = c.id and l.seq is not null and l.status in %s
				                     and (m.member_role = 'OBSERVER' or l.message_type = 'SYSTEM' or not exists (select 1 from user_blocks lb
				                          where lb.blocker_id = m.user_id and lb.blocked_id = l.sender_id))
				                   order by l.seq desc limit 1) lm on true
				where m.user_id = :u and m.left_at is null and %s
				  and (cast(:school as uuid) is null or c.school_id = :school)
				  and (cast(:student as uuid) is null or coalesce(m.on_behalf_of_student_id, c.context_student_id) = :student)
				  and (cast(:cursor_at as timestamptz) is null or (c.activity_at, c.id) < (cast(:cursor_at as timestamptz), cast(:cursor_id as uuid)))
				order by c.activity_at desc, c.id desc
				limit :n""".formatted(MessagingRows.CONVERSATION_COLUMNS, MessagingRows.MEMBER_COLUMNS, unreadSql(),
				PREVIEW_CHARS, Eligibility.accessible("ob"), VISIBLE_STATUSES, Eligibility.accessible("m")))
			.param("u", user.userId())
			.param("school", schoolFilter)
			.param("student", studentFilter)
			.param("cursor_at", (cursorAt != null) ? Timestamp.from(cursorAt) : null)
			.param("cursor_id", cursorId)
			.param("n", size + 1)
			.query((rs, i) -> new InboxRow(MessagingRows.conversation(rs), MessagingRows.member(rs), rs.getInt("unread"),
					Rows.uuid(rs, "lm_id"), rs.getString("lm_type"), rs.getString("lm_status"), Rows.uuid(rs, "lm_sender"),
					Rows.instant(rs, "lm_sent"), rs.getString("lm_preview"), Rows.uuid(rs, "other_member"),
					Rows.uuid(rs, "ctx_id"), rs.getString("ctx_name"), (Boolean) rs.getObject("guardians_can_see")))
			.list();
		boolean more = rows.size() > size;
		List<InboxRow> page = more ? rows.subList(0, size) : rows;
		Map<UUID, ConversationSummaryView> built = new LinkedHashMap<>();
		for (InboxRow row : page) {
			built.put(row.conversation().id(), summary(user, row, null));
		}
		List<ConversationSummaryView> items = new ArrayList<>(built.values());
		String next = null;
		if (more) {
			InboxRow last = page.getLast();
			next = Cursors.encode(String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, last.conversation().activityAt())),
					last.conversation().id().toString());
		}
		return new CursorPage<>(items, next);
	}

	private record InboxRow(ConversationRow conversation, MemberRow member, int unread, UUID lastId, String lastType,
			String lastStatus, UUID lastSender, Instant lastSent, String lastPreview, UUID otherMember, UUID contextId,
			String contextName, Boolean guardiansCanSee) {
	}

	/** The unread messages of one membership (alias {@code m} and {@code c}), counted up to 100. */
	private static String unreadSql() {
		return """
				(select count(*) from (select 1 from messages um where um.conversation_id = c.id and um.seq > m.last_read_seq
				    and um.sender_id <> m.user_id and um.status in ('SENT', 'EDITED') and um.message_type <> 'SYSTEM'
				    and (m.member_role = 'OBSERVER' or not exists (select 1 from user_blocks ub
				         where ub.blocker_id = m.user_id and ub.blocked_id = um.sender_id)) limit 100) un)""";
	}

	private ConversationSummaryView summary(CurrentUser user, InboxRow row, Map<UUID, ParticipantView> known) {
		ConversationRow c = row.conversation();
		MemberRow m = row.member();
		ParticipantsOf lookup = new ParticipantsOf(this.participants, this.participants.viewerOf(user.userId(), m.actingAs()));
		ParticipantView counterpart = (c.direct() && row.otherMember() != null && !m.observer())
				? lookup.of(row.otherMember()) : null;
		LastMessageView last = null;
		if (row.lastId() != null) {
			last = new LastMessageView(row.lastId(), row.lastType(), row.lastStatus(), row.lastPreview(), row.lastSent(),
					(row.lastSender() != null) ? lookup.of(row.lastSender()) : null);
		}
		boolean studentViewer = m.actingAs() == ActingAs.STUDENT;
		ContextStudentView context = null;
		if (row.contextId() != null && (!studentViewer || row.contextId().equals(user.userId()))) {
			context = new ContextStudentView(row.contextId(), row.contextName());
		}
		boolean muted = m.mutedUntil() != null && m.mutedUntil().isAfter(this.clock.instant());
		boolean canPost = !m.observer() && c.active() && (!c.moderatorsOnly() || m.role().moderates());
		return new ConversationSummaryView(c.id(), c.type(), c.directKind(), c.groupKind(), c.schoolId(), c.classId(),
				c.title(), counterpart, c.status(), c.postingPolicy(), m.role(), m.actingAs(), context,
				m.onBehalfOfStudentId(), muted, muted ? m.mutedUntil() : null, row.unread(), last, c.activityAt(),
				c.safeguarded() ? row.guardiansCanSee() : null, canPost);
	}

	/** Lazily loads participant views one by one (inbox pages are small; the cache avoids repeats). */
	private static final class ParticipantsOf {

		private final ParticipantViews views;

		private final ParticipantViews.Viewer viewer;

		private final Map<UUID, ParticipantView> cache = new LinkedHashMap<>();

		ParticipantsOf(ParticipantViews views, ParticipantViews.Viewer viewer) {
			this.views = views;
			this.viewer = viewer;
		}

		ParticipantView of(UUID memberId) {
			return this.cache.computeIfAbsent(memberId, (id) -> this.views.forMember(this.viewer, id));
		}

	}

	// ---------------------------------------------------------------------------------------------------------------
	// one conversation

	public ConversationView conversation(CurrentUser user, UUID conversationId) {
		Access access = this.policy.access(user, conversationId);
		InboxRow row = this.eligibility.statement(List.of(access.conversation().schoolId()), """
				select %s, %s,
				       %s as unread,
				       null::uuid as lm_id, null::text as lm_type, null::text as lm_status, null::uuid as lm_sender,
				       null::timestamptz as lm_sent, null::text as lm_preview,
				       (select o.id from conversation_members o where o.conversation_id = c.id and o.left_at is null
				                 and o.member_role <> 'OBSERVER' and o.user_id <> m.user_id limit 1) as other_member,
				       coalesce(m.on_behalf_of_student_id, c.context_student_id) as ctx_id,
				       (select x.display_name from users x where x.id = coalesce(m.on_behalf_of_student_id, c.context_student_id)) as ctx_name,
				       case when c.safeguarded then exists (select 1 from conversation_members ob
				            where ob.conversation_id = c.id and ob.member_role = 'OBSERVER' and %s) end as guardians_can_see
				from conversation_members m
				join conversations c on c.id = m.conversation_id
				join sp on sp.school_id = c.school_id
				where m.id = :m""".formatted(MessagingRows.CONVERSATION_COLUMNS, MessagingRows.MEMBER_COLUMNS, unreadSql(),
				Eligibility.accessible("ob")))
			.param("m", access.member().id())
			.query((rs, i) -> new InboxRow(MessagingRows.conversation(rs), MessagingRows.member(rs), rs.getInt("unread"),
					null, null, null, null, null, null, Rows.uuid(rs, "other_member"), Rows.uuid(rs, "ctx_id"),
					rs.getString("ctx_name"), (Boolean) rs.getObject("guardians_can_see")))
			.single();
		ConversationSummaryView summary = summary(user, row, null);
		boolean canModerate = access.member().role().moderates();
		return new ConversationView(summary, canModerate, row.conversation().createdAt());
	}

	public MemberListView members(CurrentUser user, UUID conversationId) {
		Access access = this.policy.access(user, conversationId);
		List<UUID> ids = this.jdbc.sql("""
				select id from conversation_members where conversation_id = :c and left_at is null
				order by (member_role = 'OBSERVER'), joined_at, id""")
			.param("c", conversationId)
			.query(UUID.class)
			.list();
		Map<UUID, ParticipantView> views = this.participants
			.forMembers(this.participants.viewerOf(user.userId(), access.member().actingAs()), ids);
		List<ParticipantView> members = ids.stream().map(views::get).toList();
		int observers = (int) members.stream().filter((p) -> p.role() == MemberRole.OBSERVER).count();
		return new MemberListView(members, observers);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// messages

	public CursorPage<MessageItemView> messages(CurrentUser user, UUID conversationId, String cursor, Integer limit) {
		Access access = this.policy.access(user, conversationId);
		int size = Cursors.clampLimit(limit);
		Long before = null;
		if (cursor != null) {
			try {
				before = Long.parseLong(Cursors.decode(cursor, 1).getFirst());
			}
			catch (NumberFormatException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<MessageRow> rows = this.jdbc.sql(MESSAGE_SELECT + """
				where m.conversation_id = :c and m.seq is not null and m.status in %s
				  and (cast(:before as bigint) is null or m.seq < :before)
				  and (:observer or m.message_type = 'SYSTEM' or not exists (select 1 from user_blocks b
				       where b.blocker_id = :viewer and b.blocked_id = m.sender_id))
				order by m.seq desc limit :n""".formatted(VISIBLE_STATUSES))
			.param("viewer", user.userId())
			.param("c", conversationId)
			.param("before", before)
			.param("observer", access.member().observer())
			.param("n", size + 1)
			.query((rs, i) -> messageRow(rs))
			.list();
		boolean more = rows.size() > size;
		List<MessageRow> page = more ? rows.subList(0, size) : rows;
		List<MessageItemView> items = views(user, access, page);
		return new CursorPage<>(items, more ? Cursors.encode(String.valueOf(page.getLast().seq())) : null);
	}

	public MessageItemView message(CurrentUser user, UUID conversationId, UUID messageId) {
		Access access = this.policy.access(user, conversationId);
		return view(user, access, messageId);
	}

	/** One message of a conversation the caller already passed {@link MessagingPolicy#access} for; 404 when they may not see it. */
	public MessageItemView view(CurrentUser user, Access access, UUID messageId) {
		List<MessageRow> rows = this.jdbc.sql(MESSAGE_SELECT + """
				where m.id = :id and m.conversation_id = :c
				  and ((m.seq is not null and m.status in %s
				        and (:observer or m.message_type = 'SYSTEM' or not exists (select 1 from user_blocks b
				             where b.blocker_id = :viewer and b.blocked_id = m.sender_id)))
				       or (m.sender_id = :viewer and m.status = 'SCHEDULED'))""".formatted(VISIBLE_STATUSES))
			.param("id", messageId)
			.param("c", access.conversation().id())
			.param("viewer", user.userId())
			.param("observer", access.member().observer())
			.query((rs, i) -> messageRow(rs))
			.list();
		if (rows.isEmpty()) {
			throw ApiException.notFound("messaging.message_not_found");
		}
		return views(user, access, rows).getFirst();
	}

	/** The caller's own SCHEDULED messages that they may still send to a conversation they can use. */
	public List<MessageItemView> scheduled(CurrentUser user) {
		List<UUID> schools = this.eligibility.schoolsOf(user.userId());
		if (schools.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = this.eligibility.statement(schools, """
				select m.id from messages m
				join conversations c on c.id = m.conversation_id
				join conversation_members cm on cm.conversation_id = c.id and cm.user_id = m.sender_id and cm.left_at is null
				join sp on sp.school_id = c.school_id
				where m.sender_id = :u and m.status = 'SCHEDULED' and %s
				order by m.send_at, m.id""".formatted(Eligibility.accessible("cm")))
			.param("u", user.userId())
			.query(UUID.class)
			.list();
		List<MessageItemView> items = new ArrayList<>();
		for (UUID id : ids) {
			UUID conv = this.jdbc.sql("select conversation_id from messages where id = :m")
				.param("m", id)
				.query(UUID.class)
				.single();
			items.add(view(user, this.policy.access(user, conv), id));
		}
		return items;
	}

	public int unreadTotal(CurrentUser user) {
		List<UUID> schools = this.eligibility.schoolsOf(user.userId());
		if (schools.isEmpty()) {
			return 0;
		}
		return this.eligibility.statement(schools, """
				select coalesce(sum(%s), 0) from conversation_members m
				join conversations c on c.id = m.conversation_id
				join sp on sp.school_id = c.school_id
				where m.user_id = :u and m.left_at is null and %s""".formatted(unreadSql(), Eligibility.accessible("m")))
			.param("u", user.userId())
			.query(Integer.class)
			.single();
	}

	private static final String MESSAGE_SELECT = """
			select m.id, m.conversation_id, m.seq, m.sender_id, m.sender_member_id, m.message_type, m.system_code, m.status,
			       case when m.status in ('SENT', 'EDITED') or (m.status = 'SCHEDULED' and m.sender_id = :viewer) then m.body end as body,
			       m.body_language_code,
			       m.reply_to_message_id, m.sent_at, m.edited_at, m.send_at,
			       case when m.sender_id = :viewer and c.conversation_type = 'DIRECT' then
			         (select min(r.read_at) from message_reads r where r.message_id = m.id and r.user_id <> m.sender_id) end as seen_at
			from messages m join conversations c on c.id = m.conversation_id
			""";

	private record MessageRow(UUID id, UUID conversationId, Long seq, UUID senderId, UUID senderMemberId, String type,
			String systemCode, String status, String body, String language, UUID replyTo, Instant sentAt, Instant editedAt,
			Instant sendAt, Instant seenAt) {
	}

	private static MessageRow messageRow(java.sql.ResultSet rs) throws java.sql.SQLException {
		long seq = rs.getLong("seq");
		return new MessageRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "conversation_id"), rs.wasNull() ? null : seq,
				Rows.uuid(rs, "sender_id"), Rows.uuid(rs, "sender_member_id"), rs.getString("message_type"),
				rs.getString("system_code"), rs.getString("status"), rs.getString("body"), rs.getString("body_language_code"),
				Rows.uuid(rs, "reply_to_message_id"), Rows.instant(rs, "sent_at"), Rows.instant(rs, "edited_at"),
				Rows.instant(rs, "send_at"), Rows.instant(rs, "seen_at"));
	}

	private List<MessageItemView> views(CurrentUser user, Access access, List<MessageRow> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = rows.stream().map(MessageRow::id).toList();
		Set<UUID> memberIds = new LinkedHashSet<>();
		rows.forEach((r) -> memberIds.add(r.senderMemberId()));
		Map<UUID, ParticipantView> people = this.participants
			.forMembers(this.participants.viewerOf(user.userId(), access.member().actingAs()), memberIds);
		Map<UUID, List<ReactionView>> reactions = reactions(user.userId(), ids);
		Map<UUID, List<AttachmentView>> attachments = attachments(rows);
		int window = this.settings.editWindowMinutes(access.conversation().schoolId());
		Instant now = this.clock.instant();
		List<MessageItemView> items = new ArrayList<>();
		for (MessageRow r : rows) {
			boolean mine = r.senderId().equals(user.userId());
			boolean live = "SENT".equals(r.status()) || "EDITED".equals(r.status());
			boolean kept = access.conversation().safeguarded() && access.member().actingAs() != ActingAs.STUDENT;
			boolean canEdit = mine && live && !kept && "TEXT".equals(r.type()) && access.conversation().active() && window > 0
					&& r.sentAt() != null && !now.isAfter(r.sentAt().plus(Duration.ofMinutes(window)))
					&& !access.member().observer();
			boolean canDelete = mine && ((live && !kept) || "SCHEDULED".equals(r.status())) && !access.member().observer();
			boolean tombstone = "DELETED".equals(r.status()) || "HIDDEN".equals(r.status());
			items.add(new MessageItemView(r.id(), r.conversationId(), people.get(r.senderMemberId()), r.type(), r.status(),
					r.body(), r.language(), r.systemCode(), r.replyTo(),
					tombstone ? List.of() : attachments.getOrDefault(r.id(), List.of()),
					tombstone ? List.of() : reactions.getOrDefault(r.id(), List.of()), mine, r.sentAt(), r.editedAt(),
					"SCHEDULED".equals(r.status()) ? r.sendAt() : null, mine ? r.seenAt() : null, canEdit, canDelete));
		}
		return items;
	}

	private Map<UUID, List<ReactionView>> reactions(UUID viewer, List<UUID> messageIds) {
		Map<UUID, List<ReactionView>> result = new LinkedHashMap<>();
		this.jdbc.sql("""
				select message_id, emoji, count(*) as n, bool_or(user_id = :u) as mine from message_reactions
				where message_id in (:ids) group by message_id, emoji order by message_id, min(created_at), emoji""")
			.param("u", viewer)
			.param("ids", messageIds)
			.query((rs, i) -> {
				result.computeIfAbsent(Rows.uuid(rs, "message_id"), (k) -> new ArrayList<>())
					.add(new ReactionView(rs.getString("emoji"), rs.getInt("n"), rs.getBoolean("mine")));
				return 0;
			})
			.list();
		return result;
	}

	private Map<UUID, List<AttachmentView>> attachments(List<MessageRow> rows) {
		List<UUID> ids = rows.stream().filter((r) -> "SENT".equals(r.status()) || "EDITED".equals(r.status())).map(MessageRow::id).toList();
		Map<UUID, List<AttachmentView>> result = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return result;
		}
		record Row(UUID message, UUID media, int order) {
		}
		List<Row> found = this.jdbc.sql("""
				select message_id, media_id, sort_order from message_attachments where message_id in (:ids)
				order by message_id, sort_order, media_id""")
			.param("ids", ids)
			.query((rs, i) -> new Row(Rows.uuid(rs, "message_id"), Rows.uuid(rs, "media_id"), rs.getInt("sort_order")))
			.list();
		Map<UUID, MediaSummary> summaries = this.media.summaries(found.stream().map(Row::media).toList());
		for (Row row : found) {
			result.computeIfAbsent(row.message(), (k) -> new ArrayList<>())
				.add(new AttachmentView(row.media(), row.order(), summaries.get(row.media())));
		}
		return result;
	}

}
