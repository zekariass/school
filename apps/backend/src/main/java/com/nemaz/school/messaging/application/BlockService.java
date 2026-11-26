package com.nemaz.school.messaging.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.messaging.application.MessagingModels.BlockView;
import com.nemaz.school.messaging.application.MessagingModels.ParticipantView;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.domain.ActingAs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blocks (rule book R7). A person is blocked <em>through a conversation member</em> (the client never learns a user id). A block
 * means: the blocked user cannot start or continue a DIRECT conversation with the blocker (they get the generic refusal), and in
 * groups the blocked user's messages are hidden from the blocker by the server ({@link MessageQueries}). Blocks never override
 * the safeguarding floor: a student cannot block their own guardians or anybody who observes a conversation they are in, and
 * an observer's view is never filtered. A student's block of an adult is audited and listed for reviewers.
 */
@Service
public class BlockService {

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final ParticipantViews participants;

	private final AuditLog audit;

	BlockService(JdbcClient jdbc, MessagingPolicy policy, ParticipantViews participants, AuditLog audit) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.participants = participants;
		this.audit = audit;
	}

	@Transactional
	public BlockView create(CurrentUser user, UUID conversationId, UUID memberId) {
		Access access = this.policy.access(user, conversationId);
		if (access.member().observer()) {
			throw MessagingPolicy.notAllowed();
		}
		record Target(UUID userId, ActingAs acting, boolean observer) {
		}
		Target target = this.jdbc.sql("select user_id, acting_as, member_role from conversation_members where id = :m and conversation_id = :c")
			.param("m", memberId)
			.param("c", conversationId)
			.query((rs, i) -> new Target(Rows.uuid(rs, "user_id"), ActingAs.valueOf(rs.getString("acting_as")),
					"OBSERVER".equals(rs.getString("member_role"))))
			.optional()
			.orElseThrow(() -> ApiException.notFound("messaging.member_not_found"));
		UUID me = user.userId();
		if (target.userId().equals(me)) {
			throw ApiException.badRequest("messaging.cannot_block_self");
		}
		boolean student = access.member().actingAs() == ActingAs.STUDENT;
		if (student && (target.observer() || isGuardianOf(target.userId(), me) || observesSomethingOf(target.userId(), me))) {
			throw ApiException.forbidden("messaging.cannot_block");
		}
		boolean studentBlockedAdult = student && target.acting() != ActingAs.STUDENT;
		int inserted = this.jdbc.sql("""
				insert into user_blocks (blocker_id, blocked_id, school_id, blocker_is_student, via_conversation_id)
				values (:me, :them, :s, :student, :c) on conflict do nothing""")
			.param("me", me)
			.param("them", target.userId())
			.param("s", access.conversation().schoolId())
			.param("student", student)
			.param("c", conversationId)
			.update();
		UUID blockId = this.jdbc.sql("select id from user_blocks where blocker_id = :me and blocked_id = :them")
			.param("me", me)
			.param("them", target.userId())
			.query(UUID.class)
			.single();
		if (inserted > 0) {
			this.audit.record(AuditEntry.of("messaging.block.create", AuditOutcome.SUCCESS)
				.target("conversation", conversationId)
				.school(access.conversation().schoolId())
				.metadata("blockId", blockId.toString())
				.metadata("blockerId", me.toString())
				.metadata("blockedId", target.userId().toString())
				.metadata("studentBlockedAdult", studentBlockedAdult));
		}
		return list(user).stream().filter((b) -> b.id().equals(blockId)).findFirst().orElseThrow();
	}

	private boolean isGuardianOf(UUID guardianId, UUID studentId) {
		return this.jdbc.sql("""
				select exists (select 1 from student_guardians where guardian_id = :g and student_id = :s and status in ('ACTIVE', 'PENDING'))""")
			.param("g", guardianId)
			.param("s", studentId)
			.query(Boolean.class)
			.single();
	}

	/** True when {@code observerId} observes some conversation the student takes part in. */
	private boolean observesSomethingOf(UUID observerId, UUID studentId) {
		return this.jdbc.sql("""
				select exists (select 1 from conversation_members o join conversation_members s on s.conversation_id = o.conversation_id
				               where o.user_id = :o and o.member_role = 'OBSERVER' and s.user_id = :s)""")
			.param("o", observerId)
			.param("s", studentId)
			.query(Boolean.class)
			.single();
	}

	@Transactional(readOnly = true)
	public List<BlockView> list(CurrentUser user) {
		record Row(UUID id, UUID conversationId, java.time.Instant createdAt, UUID memberId, ActingAs viewerActing) {
		}
		List<Row> rows = this.jdbc.sql("""
				select b.id, b.via_conversation_id, b.created_at,
				       (select m.id from conversation_members m where m.conversation_id = b.via_conversation_id and m.user_id = b.blocked_id
				        order by m.left_at is null desc, m.joined_at desc limit 1) as member_id,
				       (select v.acting_as from conversation_members v where v.conversation_id = b.via_conversation_id and v.user_id = b.blocker_id
				        order by v.left_at is null desc, v.joined_at desc limit 1) as viewer_acting
				from user_blocks b where b.blocker_id = :u order by b.created_at desc, b.id desc""")
			.param("u", user.userId())
			.query((rs, i) -> new Row(Rows.uuid(rs, "id"), Rows.uuid(rs, "via_conversation_id"), Rows.instant(rs, "created_at"),
					Rows.uuid(rs, "member_id"), (rs.getString("viewer_acting") != null) ? ActingAs.valueOf(rs.getString("viewer_acting")) : ActingAs.STAFF))
			.list();
		List<BlockView> views = new ArrayList<>();
		for (Row row : rows) {
			ParticipantView person = (row.memberId() != null)
					? this.participants.forMember(this.participants.viewerOf(user.userId(), row.viewerActing()), row.memberId())
					: null;
			views.add(new BlockView(row.id(), row.conversationId(), person, row.createdAt()));
		}
		return views;
	}

	@Transactional
	public void delete(CurrentUser user, UUID blockId) {
		UUID school = this.jdbc.sql("select school_id from user_blocks where id = :b and blocker_id = :u")
			.param("b", blockId)
			.param("u", user.userId())
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("messaging.block_not_found"));
		this.jdbc.sql("delete from user_blocks where id = :b and blocker_id = :u").param("b", blockId).param("u", user.userId()).update();
		this.audit.record(AuditEntry.of("messaging.block.delete", AuditOutcome.SUCCESS)
			.target("user_block", blockId)
			.school(school));
	}

}
