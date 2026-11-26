package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.messaging.application.MessagingModels.ConversationView;
import com.nemaz.school.messaging.application.MessagingModels.GroupAdminView;
import com.nemaz.school.messaging.application.MessagingModels.MemberListView;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.application.MessagingPolicy.DirectPlan;
import com.nemaz.school.messaging.application.MessagingPolicy.GroupBasis;
import com.nemaz.school.messaging.application.MessagingPolicy.Moderation;
import com.nemaz.school.messaging.application.MessagingPolicy.Party;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.GroupKind;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating conversations and managing their members and settings (rule book R2-R6). The pattern for every creation: decide the
 * kind with {@link MessagingPolicy} (a coarse plan), insert the rows, then run the <em>live</em> check of {@link Eligibility} on
 * what was inserted inside the same transaction and roll back when anybody would not pass it. What can be created is therefore
 * exactly what can be read, and the settings of the kind (R2-R4) are enforced in the one SQL place.
 */
@Service
public class ConversationService {

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final MessageQueries queries;

	private final Eligibility eligibility;

	private final Safeguarding safeguarding;

	private final RosterSync roster;

	private final NotificationService notifications;

	private final AuditLog audit;

	private final ClassAccess classes;

	private final SchoolGate gate;

	private final MessagingSettings settings;

	private final Clock clock;

	ConversationService(JdbcClient jdbc, MessagingPolicy policy, MessageQueries queries, Eligibility eligibility,
			Safeguarding safeguarding, RosterSync roster, NotificationService notifications, AuditLog audit,
			ClassAccess classes, SchoolGate gate, MessagingSettings settings, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.queries = queries;
		this.eligibility = eligibility;
		this.safeguarding = safeguarding;
		this.roster = roster;
		this.notifications = notifications;
		this.audit = audit;
		this.classes = classes;
		this.gate = gate;
		this.settings = settings;
		this.clock = clock;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// DIRECT

	public record DirectResult(ConversationView conversation, boolean created) {
	}

	/** Starts (or returns) the DIRECT conversation with {@code recipientId}; see {@link MessagingPolicy#planDirect}. */
	@Transactional
	public DirectResult startDirect(CurrentUser user, UUID schoolId, UUID recipientId, UUID contextStudentId) {
		this.policy.requireSchool(user, schoolId);
		DirectPlan plan = this.policy.planDirect(user, schoolId, recipientId, contextStudentId);
		String key = plan.key();
		UUID existing = findDirect(schoolId, key);
		if (existing != null) {
			return existingDirect(user, existing);
		}
		UUID id = Ids.newId();
		boolean safeguarded = plan.kind() == com.nemaz.school.messaging.domain.DirectKind.TEACHER_STUDENT;
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		int inserted = this.jdbc.sql("""
				insert into conversations (id, conversation_type, school_id, context_student_id, direct_kind, direct_key,
				                           safeguarded, created_by, activity_at, created_at)
				values (:id, 'DIRECT', :s, :ctx, :kind, :key, :safeguarded, :u, :now, :now)
				on conflict (school_id, direct_key) where direct_key is not null do nothing""")
			.param("id", id)
			.param("s", schoolId)
			.param("ctx", (plan.kind() == com.nemaz.school.messaging.domain.DirectKind.STUDENT_STUDENT) ? null
					: plan.contextStudentId())
			.param("kind", plan.kind().name())
			.param("key", key)
			.param("safeguarded", safeguarded)
			.param("u", user.userId())
			.param("now", now)
			.update();
		if (inserted == 0) {
			UUID winner = findDirect(schoolId, key);
			if (winner == null) {
				throw MessagingPolicy.notAllowed();
			}
			return existingDirect(user, winner);
		}
		insertMember(id, schoolId, plan.caller(), "MEMBER", user.userId(), now);
		insertMember(id, schoolId, plan.recipient(), "MEMBER", user.userId(), now);
		if (!this.eligibility.isAccessible(id, user.userId())) {
			// a refusal after the rows exist: roll everything back (the exception does) and say only "not allowed"
			throw MessagingPolicy.notAllowed();
		}
		if (safeguarded) {
			this.safeguarding.reconcile(id);
		}
		this.audit.record(AuditEntry.of("messaging.conversation.create", AuditOutcome.SUCCESS)
			.target("conversation", id)
			.school(schoolId)
			.metadata("kind", plan.kind().name())
			.metadata("safeguarded", safeguarded)
			.metadata("initiatorId", user.userId().toString())
			.metadata("recipientId", recipientId.toString())
			.metadata("studentId", (plan.contextStudentId() != null) ? plan.contextStudentId().toString() : null)
			.metadata("adminVisible", safeguarded ? adminVisible(id) : null));
		return new DirectResult(this.queries.conversation(user, id), true);
	}

	private DirectResult existingDirect(CurrentUser user, UUID existing) {
		if (this.policy.find(user.userId(), existing, false).isEmpty()) {
			throw MessagingPolicy.notAllowed();
		}
		return new DirectResult(this.queries.conversation(user, existing), false);
	}

	private boolean adminVisible(UUID id) {
		return this.jdbc.sql("select admin_visible from conversations where id = :c")
			.param("c", id)
			.query(Boolean.class)
			.single();
	}

	private UUID findDirect(UUID schoolId, String key) {
		return this.jdbc.sql("select id from conversations where school_id = :s and direct_key = :k")
			.param("s", schoolId)
			.param("k", key)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	private void insertMember(UUID conversationId, UUID schoolId, Party party, String role, UUID addedBy, Timestamp now) {
		this.jdbc.sql("""
				insert into conversation_members (id, conversation_id, school_id, user_id, member_role, acting_as,
				                                  on_behalf_of_student_id, joined_at, added_by)
				values (:id, :c, :s, :u, :role, :acting, :st, :now, :by)""")
			.param("id", Ids.newId())
			.param("c", conversationId)
			.param("s", schoolId)
			.param("u", party.userId())
			.param("role", role)
			.param("acting", party.actingAs().name())
			.param("st", party.onBehalfOfStudentId())
			.param("now", now)
			.param("by", addedBy)
			.update();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// groups

	public record GroupCommand(GroupKind kind, UUID classId, String title, List<UUID> memberUserIds, String postingPolicy) {

		public GroupCommand {
			memberUserIds = (memberUserIds == null) ? List.of() : List.copyOf(new LinkedHashSet<>(memberUserIds));
		}

	}

	@Transactional
	public GroupAdminView createGroup(CurrentUser user, UUID schoolId, GroupCommand cmd) {
		this.policy.requireSchool(user, schoolId);
		if (cmd.kind() == null) {
			throw ApiException.badRequest("messaging.group_kind_required");
		}
		GroupKind kind = cmd.kind();
		UUID classId = kind.classBound() ? requireClass(user, schoolId, cmd.classId()) : null;
		GroupBasis basis = this.policy.groupBasis(user, schoolId, kind, classId);
		String title = (cmd.title() == null || cmd.title().isBlank()) ? null : cmd.title().strip();
		if (title != null && title.codePointCount(0, title.length()) > 120) {
			throw ApiException.badRequest("messaging.title_too_long");
		}
		if (title == null && !kind.rosterDriven()) {
			throw ApiException.badRequest("messaging.title_required");
		}
		String posting = postingPolicy(cmd.postingPolicy(), (kind == GroupKind.SCHOOL_STAFF) ? "MODERATORS_ONLY" : "ALL_MEMBERS");
		UUID id = Ids.newId();
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		try {
			this.jdbc.sql("""
					insert into conversations (id, conversation_type, school_id, class_id, group_kind, title, posting_policy,
					                           created_by, activity_at, created_at)
					values (:id, :type, :s, :class, :kind, :title, :posting, :u, :now, :now)""")
				.param("id", id)
				.param("type", kind.type().name())
				.param("s", schoolId)
				.param("class", classId)
				.param("kind", kind.name())
				.param("title", title)
				.param("posting", posting)
				.param("u", user.userId())
				.param("now", now)
				.update();
		}
		catch (org.springframework.dao.DuplicateKeyException ex) {
			throw ApiException.conflict("messaging.group_exists");
		}
		if (kind.rosterDriven()) {
			this.roster.reconcileConversation(id);
		}
		else {
			if (basis == GroupBasis.STUDENT_PROPOSAL) {
				addExplicit(id, schoolId, kind, classId, user.userId(), "MEMBER", user.userId(), now);
			}
			else {
				// the creator owns it when they can take part in it (an administrator without a staff row moderates from outside)
				if (actingFor(kind, schoolId, classId, user.userId()) != null) {
					addExplicit(id, schoolId, kind, classId, user.userId(), "OWNER", user.userId(), now);
				}
			}
			for (UUID member : cmd.memberUserIds()) {
				if (!member.equals(user.userId())) {
					addExplicit(id, schoolId, kind, classId, member, "MEMBER", user.userId(), now);
				}
			}
			if (kind == GroupKind.STUDENT_GROUP && studentCount(id) < 2) {
				throw ApiException.badRequest("messaging.group_too_small");
			}
			verifyMembers(id);
		}
		boolean pending = basis == GroupBasis.STUDENT_PROPOSAL;
		if (pending) {
			this.jdbc.sql("update conversations set status = 'PENDING_APPROVAL' where id = :c").param("c", id).update();
			notifyApprovers(schoolId, classId, id);
		}
		this.audit.record(AuditEntry.of("messaging.conversation.create", AuditOutcome.SUCCESS)
			.target("conversation", id)
			.school(schoolId)
			.metadata("kind", kind.name())
			.metadata("classId", (classId != null) ? classId.toString() : null)
			.metadata("pendingApproval", pending)
			.metadata("members", memberCount(id)));
		return groupView(user.userId(), id);
	}

	private UUID requireClass(CurrentUser user, UUID schoolId, UUID classId) {
		if (classId == null) {
			throw ApiException.badRequest("messaging.class_required");
		}
		ClassRef ref = this.classes.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.classes.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		return classId;
	}

	private String postingPolicy(String requested, String fallback) {
		if (requested == null || requested.isBlank()) {
			return fallback;
		}
		if (!"ALL_MEMBERS".equals(requested) && !"MODERATORS_ONLY".equals(requested)) {
			throw ApiException.badRequest("messaging.posting_policy_invalid");
		}
		return requested;
	}

	/** The capacity a user would have in a group of that kind, or null when they cannot be in it (not eligible). */
	private ActingAs actingFor(GroupKind kind, UUID schoolId, UUID classId, UUID userId) {
		if (kind == GroupKind.STAFF || kind == GroupKind.SCHOOL_STAFF) {
			String type = this.jdbc.sql("""
					select staff_type from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE'""")
				.param("s", schoolId)
				.param("u", userId)
				.query(String.class)
				.optional()
				.orElse(null);
			return (type == null) ? null : ("TEACHER".equals(type) ? ActingAs.TEACHER : ActingAs.STAFF);
		}
		boolean student = this.jdbc.sql("select exists (select 1 from class_students where class_id = :c and student_id = :u and status = 'ACTIVE')")
			.param("c", classId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
		if (student) {
			return ActingAs.STUDENT;
		}
		boolean teacher = this.jdbc.sql("""
				select exists (select 1 from class_teachers where class_id = :c and teacher_id = :u and status = 'ACTIVE'
				               and role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER'))""")
			.param("c", classId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
		return teacher ? ActingAs.TEACHER : null;
	}

	private void addExplicit(UUID conversationId, UUID schoolId, GroupKind kind, UUID classId, UUID userId, String role,
			UUID addedBy, Timestamp now) {
		ActingAs acting = actingFor(kind, schoolId, classId, userId);
		if (acting == null) {
			throw ApiException.badRequest("messaging.member_not_eligible");
		}
		this.jdbc.sql("""
				insert into conversation_members (id, conversation_id, school_id, user_id, member_role, acting_as, joined_at, added_by)
				values (:id, :c, :s, :u, :role, :acting, :now, :by)
				on conflict (conversation_id, user_id) where left_at is null do nothing""")
			.param("id", Ids.newId())
			.param("c", conversationId)
			.param("s", schoolId)
			.param("u", userId)
			.param("role", role)
			.param("acting", acting.name())
			.param("now", now)
			.param("by", addedBy)
			.update();
	}

	/** Every live member of the (just created or changed) group must pass the live check; 400 otherwise (the transaction rolls back). */
	private void verifyMembers(UUID conversationId) {
		List<UUID> users = this.jdbc.sql("select user_id from conversation_members where conversation_id = :c and left_at is null")
			.param("c", conversationId)
			.query(UUID.class)
			.list();
		for (UUID userId : users) {
			if (!this.eligibility.isAccessible(conversationId, userId)) {
				throw ApiException.badRequest("messaging.member_not_eligible");
			}
		}
	}

	/** A conversation with students needs at least two of them: with one it would be a private chat without the safeguarding floor. */
	private int studentCount(UUID id) {
		return this.jdbc.sql("select count(*) from conversation_members where conversation_id = :c and left_at is null and acting_as = 'STUDENT'")
			.param("c", id)
			.query(Integer.class)
			.single();
	}

	private int memberCount(UUID id) {
		return this.jdbc.sql("select count(*) from conversation_members where conversation_id = :c and left_at is null")
			.param("c", id)
			.query(Integer.class)
			.single();
	}

	private void notifyApprovers(UUID schoolId, UUID classId, UUID conversationId) {
		List<UUID> teachers = this.jdbc.sql("""
				select distinct teacher_id from class_teachers where class_id = :c and status = 'ACTIVE' and role in ('LEAD', 'CO_TEACHER')""")
			.param("c", classId)
			.query(UUID.class)
			.list();
		if (teachers.isEmpty()) {
			return;
		}
		this.notifications.notify(NotificationRequest.to("GROUP_APPROVAL_REQUESTED", teachers)
			.school(schoolId)
			.source("conversation", conversationId)
			.path("/messages/groups")
			.data("conversationId", conversationId.toString())
			.build());
	}

	// ---------------------------------------------------------------------------------------------------------------
	// approval of proposed student groups

	@Transactional
	public GroupAdminView approve(CurrentUser user, UUID conversationId) {
		ConversationRow conv = pendingGroup(user, conversationId);
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		this.jdbc.sql("update conversations set status = 'ACTIVE', approved_by = :u, approved_at = :now where id = :c")
			.param("u", user.userId())
			.param("now", now)
			.param("c", conversationId)
			.update();
		// a group with students always has teacher moderators: the class's LEAD and CO_TEACHERs; the approver owns it when they are one
		List<UUID> teachers = this.jdbc.sql("""
				select distinct teacher_id from class_teachers where class_id = :c and status = 'ACTIVE' and role in ('LEAD', 'CO_TEACHER')""")
			.param("c", conv.classId())
			.query(UUID.class)
			.list();
		for (UUID teacher : teachers) {
			this.jdbc.sql("""
					insert into conversation_members (id, conversation_id, school_id, user_id, member_role, acting_as, joined_at, added_by)
					values (:id, :c, :s, :u, :role, 'TEACHER', :now, :by)
					on conflict (conversation_id, user_id) where left_at is null do nothing""")
				.param("id", Ids.newId())
				.param("c", conversationId)
				.param("s", conv.schoolId())
				.param("u", teacher)
				.param("role", teacher.equals(user.userId()) ? "OWNER" : "MODERATOR")
				.param("now", now)
				.param("by", user.userId())
				.update();
		}
		verifyMembers(conversationId);
		this.audit.record(AuditEntry.of("messaging.conversation.approve", AuditOutcome.SUCCESS)
			.target("conversation", conversationId)
			.school(conv.schoolId())
			.metadata("classId", conv.classId().toString()));
		return groupView(user.userId(), conversationId);
	}

	@Transactional
	public GroupAdminView reject(CurrentUser user, UUID conversationId) {
		ConversationRow conv = pendingGroup(user, conversationId);
		this.jdbc.sql("update conversations set status = 'REJECTED', approved_by = :u, approved_at = now() where id = :c")
			.param("u", user.userId())
			.param("c", conversationId)
			.update();
		this.audit.record(AuditEntry.of("messaging.conversation.reject", AuditOutcome.SUCCESS)
			.target("conversation", conversationId)
			.school(conv.schoolId())
			.metadata("classId", conv.classId().toString()));
		return groupView(user.userId(), conversationId);
	}

	private ConversationRow pendingGroup(CurrentUser user, UUID conversationId) {
		ConversationRow conv = this.policy.conversation(conversationId).orElseThrow(MessagingPolicy::notFound);
		if (conv.classId() == null) {
			throw MessagingPolicy.notFound();
		}
		this.policy.requireFeature(conv.schoolId());
		boolean approver = this.classes.canLead(user, conv.classId())
				|| this.policy.messagingTeacherRole(user, conv.classId())
					.filter((r) -> r == com.nemaz.school.academic.ClassMembershipView.TeacherRole.CO_TEACHER)
					.isPresent()
				|| this.gate.can(user, conv.schoolId(), Permissions.SCHOOL_MESSAGING_MODERATE);
		if (!approver) {
			throw conv.createdBy().equals(user.userId()) ? ApiException.forbidden("messaging.moderation_not_allowed")
					: MessagingPolicy.notFound();
		}
		if (!"PENDING_APPROVAL".equals(conv.status())) {
			throw ApiException.conflict("messaging.not_pending");
		}
		return conv;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// members and settings

	@Transactional
	public MemberListView addMembers(CurrentUser user, UUID conversationId, List<UUID> userIds) {
		Moderation moderation = this.policy.moderation(user, conversationId);
		ConversationRow conv = moderation.conversation();
		if (conv.groupKind().rosterDriven()) {
			throw ApiException.conflict("messaging.roster_managed");
		}
		if (!"ACTIVE".equals(conv.status()) && !"LOCKED".equals(conv.status())) {
			throw ApiException.conflict("messaging.conversation_closed");
		}
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		Set<UUID> added = new LinkedHashSet<>();
		for (UUID userId : new LinkedHashSet<>(userIds)) {
			boolean already = this.jdbc.sql("select exists (select 1 from conversation_members where conversation_id = :c and user_id = :u and left_at is null)")
				.param("c", conversationId)
				.param("u", userId)
				.query(Boolean.class)
				.single();
			if (!already) {
				addExplicit(conversationId, conv.schoolId(), conv.groupKind(), conv.classId(), userId, "MEMBER",
						user.userId(), now);
				added.add(userId);
			}
		}
		verifyMembers(conversationId);
		if (!added.isEmpty()) {
			this.audit.record(AuditEntry.of("messaging.member.add", AuditOutcome.SUCCESS)
				.target("conversation", conversationId)
				.school(conv.schoolId())
				.metadata("count", added.size())
				.metadata("userIds", added.stream().map(UUID::toString).toList()));
		}
		return memberListFor(user, moderation);
	}

	/** Ends a member's membership (the moderator gives a reason, audited). Observers cannot be removed (the floor). */
	@Transactional
	public MemberListView removeMember(CurrentUser user, UUID conversationId, UUID memberId, String reason) {
		Moderation moderation = this.policy.moderation(user, conversationId);
		ConversationRow conv = moderation.conversation();
		String clean = (reason == null) ? "" : com.nemaz.school.messaging.domain.MessageText.clean(reason);
		if (clean.isEmpty()) {
			throw ApiException.badRequest("messaging.reason_required");
		}
		if (clean.length() > 200) {
			throw ApiException.badRequest("messaging.reason_too_long");
		}
		record Target(UUID userId, String role, String acting) {
		}
		Target target = this.jdbc.sql("""
				select user_id, member_role, acting_as from conversation_members
				where id = :m and conversation_id = :c and left_at is null""")
			.param("m", memberId)
			.param("c", conversationId)
			.query((rs, i) -> new Target(rs.getObject("user_id", UUID.class), rs.getString("member_role"), rs.getString("acting_as")))
			.optional()
			.orElseThrow(() -> ApiException.notFound("messaging.member_not_found"));
		if ("OBSERVER".equals(target.role())) {
			throw ApiException.forbidden("messaging.moderation_not_allowed");
		}
		boolean teacherModerator = "TEACHER".equals(target.acting())
				&& ("MODERATOR".equals(target.role()) || "OWNER".equals(target.role()));
		if (conv.groupKind() == GroupKind.STUDENT_GROUP && teacherModerator && teacherModerators(conversationId) <= 1) {
			throw ApiException.conflict("messaging.teacher_required");
		}
		if (conv.groupKind() == GroupKind.STUDENT_GROUP && "STUDENT".equals(target.acting()) && studentCount(conversationId) <= 2) {
			throw ApiException.conflict("messaging.group_too_small");
		}
		this.jdbc.sql("""
				update conversation_members set left_at = now(), left_reason = 'REMOVED', removed_by = :u
				where id = :m and left_at is null""")
			.param("u", user.userId())
			.param("m", memberId)
			.update();
		this.audit.record(AuditEntry.of("messaging.member.remove", AuditOutcome.SUCCESS)
			.target("conversation", conversationId)
			.school(conv.schoolId())
			.metadata("memberId", memberId.toString())
			.metadata("removedUserId", target.userId().toString())
			.metadata("reason", clean));
		return memberListFor(user, moderation);
	}

	private int teacherModerators(UUID conversationId) {
		return this.jdbc.sql("""
				select count(*) from conversation_members where conversation_id = :c and left_at is null
				  and acting_as = 'TEACHER' and member_role in ('OWNER', 'MODERATOR')""")
			.param("c", conversationId)
			.query(Integer.class)
			.single();
	}

	/** A member leaves a group themself (not a DIRECT conversation, not as an observer); the roster does not add them back. */
	@Transactional
	public void leave(CurrentUser user, UUID conversationId) {
		Access access = this.policy.access(user, conversationId);
		if (access.conversation().direct() || access.member().observer()) {
			throw ApiException.forbidden("messaging.leave_not_allowed");
		}
		this.jdbc.sql("update conversation_members set left_at = now(), left_reason = 'LEFT' where id = :m and left_at is null")
			.param("m", access.member().id())
			.update();
		this.audit.record(AuditEntry.of("messaging.member.leave", AuditOutcome.SUCCESS)
			.target("conversation", conversationId)
			.school(access.conversation().schoolId()));
	}

	public record UpdateCommand(String title, String postingPolicy, String status) {
	}

	/** Title, posting policy and lock/archive of a group. */
	@Transactional
	public GroupAdminView update(CurrentUser user, UUID conversationId, UpdateCommand cmd) {
		Moderation moderation = this.policy.moderation(user, conversationId);
		ConversationRow conv = moderation.conversation();
		if (!List.of("ACTIVE", "LOCKED", "ARCHIVED").contains(conv.status())) {
			throw ApiException.conflict("messaging.conversation_closed");
		}
		if (cmd.title() != null) {
			String title = cmd.title().strip();
			if (title.isEmpty() || title.codePointCount(0, title.length()) > 120) {
				throw ApiException.badRequest("messaging.title_too_long");
			}
			this.jdbc.sql("update conversations set title = :t, updated_at = now() where id = :c")
				.param("t", title)
				.param("c", conversationId)
				.update();
		}
		if (cmd.postingPolicy() != null) {
			String posting = postingPolicy(cmd.postingPolicy(), "ALL_MEMBERS");
			this.jdbc.sql("update conversations set posting_policy = :p, updated_at = now() where id = :c")
				.param("p", posting)
				.param("c", conversationId)
				.update();
		}
		if (cmd.status() != null) {
			if (!List.of("ACTIVE", "LOCKED", "ARCHIVED").contains(cmd.status())) {
				throw ApiException.badRequest("messaging.status_invalid");
			}
			this.jdbc.sql("update conversations set status = :s, updated_at = now() where id = :c")
				.param("s", cmd.status())
				.param("c", conversationId)
				.update();
		}
		this.audit.record(AuditEntry.of("messaging.conversation.update", AuditOutcome.SUCCESS)
			.target("conversation", conversationId)
			.school(conv.schoolId())
			.metadata("posting", cmd.postingPolicy())
			.metadata("status", cmd.status())
			.metadata("titleChanged", cmd.title() != null));
		return groupView(user.userId(), conversationId);
	}

	private MemberListView memberListFor(CurrentUser user, Moderation moderation) {
		if (moderation.member() != null) {
			return this.queries.members(user, moderation.conversation().id());
		}
		return new MemberListView(List.of(), 0);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// group lists for moderators and proposers (metadata only, never messages)

	public List<GroupAdminView> groups(CurrentUser user, UUID schoolId, String status) {
		this.policy.requireSchool(user, schoolId);
		boolean moderator = this.gate.can(user, schoolId, Permissions.SCHOOL_MESSAGING_MODERATE);
		List<GroupAdminView> result = new ArrayList<>();
		this.jdbc.sql("""
				select c.id from conversations c
				where c.school_id = :s and c.conversation_type <> 'DIRECT' and (cast(:status as text) is null or c.status = :status)
				  and (:moderator or c.created_by = :u
				       or (c.class_id is not null and exists (select 1 from class_teachers ct where ct.class_id = c.class_id
				            and ct.teacher_id = :u and ct.status = 'ACTIVE' and ct.role in ('LEAD', 'CO_TEACHER'))))
				order by c.created_at desc, c.id desc limit 100""")
			.param("s", schoolId)
			.param("status", status)
			.param("moderator", moderator)
			.param("u", user.userId())
			.query(UUID.class)
			.list()
			.forEach((id) -> result.add(groupView(user.userId(), id)));
		return result;
	}

	GroupAdminView groupView(UUID viewerId, UUID conversationId) {
		return this.jdbc.sql("""
				select c.id, c.conversation_type, c.group_kind, c.class_id, c.title, c.status, c.posting_policy, c.created_by,
				       c.created_at, (select count(*) from conversation_members m where m.conversation_id = c.id and m.left_at is null) as n
				from conversations c where c.id = :c""")
			.param("c", conversationId)
			.query((rs, i) -> new GroupAdminView(Rows.uuid(rs, "id"),
					com.nemaz.school.messaging.domain.ConversationType.valueOf(rs.getString("conversation_type")),
					GroupKind.valueOf(rs.getString("group_kind")), Rows.uuid(rs, "class_id"), rs.getString("title"),
					rs.getString("status"), rs.getString("posting_policy"), rs.getInt("n"),
					viewerId.equals(Rows.uuid(rs, "created_by")), Rows.instant(rs, "created_at")))
			.single();
	}

}
