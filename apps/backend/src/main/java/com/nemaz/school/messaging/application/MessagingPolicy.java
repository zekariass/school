package com.nemaz.school.messaging.application;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassMembershipView;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.DirectKind;
import com.nemaz.school.messaging.domain.GroupKind;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.messaging.infrastructure.MessagingRows.MemberRow;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The policy engine of messaging</b>: every write and every read asks it, so the rule book (docs/Messaging_Rules.md) lives
 * in one place. Three questions:
 * <ol>
 * <li><b>May this person use this conversation right now?</b> ({@link #access}) A membership row is necessary, never
 * sufficient: the live rules of {@link Eligibility} (R1) must hold on this request, otherwise the answer is 404 (the
 * conversation might as well not exist) and, when the school switched the {@code messaging} flag off, 403.</li>
 * <li><b>May they write to it?</b> ({@link #requirePostable}) Observers never; closed conversations never;
 * moderators-only conversations only for owners and moderators; a DIRECT conversation never across a block.</li>
 * <li><b>May they start this conversation?</b> ({@link #planDirect}, {@link #groupBasis}) Who may start which kind of DIRECT or
 * group conversation, from the person's relationships and the school's settings. The plan is a coarse decision of <em>which
 * kind</em>; the conversation service then creates the rows and runs the live check of {@link Eligibility} on them in the same
 * transaction, so what can be created is exactly what can be read.</li>
 * </ol>
 * Refusals that the refused party meets are the generic {@code messaging.not_allowed}: they never say whether the reason was a
 * block, a restricted guardian link, a switched-off setting or an unknown person.
 */
@Component
@Transactional(readOnly = true)
public class MessagingPolicy {

	private final JdbcClient jdbc;

	private final Eligibility eligibility;

	private final MessagingSettings settings;

	private final SchoolPeople people;

	private final GuardianAccess guardians;

	private final ClassAccess classes;

	private final SchoolGate gate;

	MessagingPolicy(JdbcClient jdbc, Eligibility eligibility, MessagingSettings settings, SchoolPeople people,
			GuardianAccess guardians, ClassAccess classes, SchoolGate gate) {
		this.jdbc = jdbc;
		this.eligibility = eligibility;
		this.settings = settings;
		this.people = people;
		this.guardians = guardians;
		this.classes = classes;
		this.gate = gate;
	}

	/** A conversation the user may use now and their membership of it. */
	public record Access(ConversationRow conversation, MemberRow member) {

		public UUID userId() {
			return this.member.userId();
		}

	}

	public static ApiException notAllowed() {
		return ApiException.forbidden("messaging.not_allowed");
	}

	public static ApiException notFound() {
		return ApiException.notFound("messaging.conversation_not_found");
	}

	// ---------------------------------------------------------------------------------------------------------------
	// reading

	public Optional<ConversationRow> conversation(UUID conversationId) {
		return this.jdbc.sql("select " + MessagingRows.CONVERSATION_COLUMNS + " from conversations c where c.id = :c")
			.param("c", conversationId)
			.query((rs, i) -> MessagingRows.conversation(rs))
			.optional();
	}

	/** The conversation the user may use now: 404 for anybody else, 403 when the school switched messaging off. */
	public Access access(CurrentUser user, UUID conversationId) {
		return find(user.userId(), conversationId, true).orElseThrow(MessagingPolicy::notFound);
	}

	/** Like {@link #access} but empty instead of 404 (the feature flag still answers 403 to a live member). */
	public Optional<Access> find(UUID userId, UUID conversationId, boolean failOnFeature) {
		ConversationRow conv = conversation(conversationId).orElse(null);
		if (conv == null) {
			return Optional.empty();
		}
		MemberRow member = this.jdbc.sql("select " + MessagingRows.MEMBER_COLUMNS
				+ " from conversation_members m where m.conversation_id = :c and m.user_id = :u and m.left_at is null")
			.param("c", conversationId)
			.param("u", userId)
			.query((rs, i) -> MessagingRows.member(rs))
			.optional()
			.orElse(null);
		if (member == null) {
			return Optional.empty();
		}
		if (!this.settings.featureEnabled(conv.schoolId())) {
			if (failOnFeature) {
				throw ApiException.forbidden("messaging.feature_disabled");
			}
			return Optional.empty();
		}
		if (!this.eligibility.isAccessible(conversationId, userId)) {
			return Optional.empty();
		}
		return Optional.of(new Access(conv, member));
	}

	/** 404 unless the school is visible to the caller (member, guardian of one of its students, or an administrator); 403 when messaging is off. */
	public void requireSchool(CurrentUser user, UUID schoolId) {
		boolean guardian = this.jdbc.sql("""
				select exists (select 1 from student_guardians g
				               join school_students ss on ss.student_id = g.student_id and ss.status = 'ACTIVE'
				               where g.guardian_id = :u and ss.school_id = :s and g.status = 'ACTIVE' and not g.custody_restricted)""")
			.param("u", user.userId())
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
		if (!guardian) {
			this.gate.requireVisible(user, schoolId);
		}
		requireFeature(schoolId);
	}

	public void requireFeature(UUID schoolId) {
		if (!this.settings.featureEnabled(schoolId)) {
			throw ApiException.forbidden("messaging.feature_disabled");
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// writing

	/** Throws unless the member may write to the conversation now (the live access check is {@link #access}'s). */
	public void requirePostable(Access access) {
		ApiException refusal = postRefusal(access);
		if (refusal != null) {
			throw refusal;
		}
	}

	/**
	 * Why the member may not write to the conversation now, or null when they may. Does not throw (an exception crossing this
	 * transactional bean would mark an enclosing transaction rollback-only even when the caller handles it).
	 */
	public ApiException postRefusal(Access access) {
		MemberRow member = access.member();
		ConversationRow conv = access.conversation();
		if (member.observer()) {
			return ApiException.forbidden("messaging.observer_read_only");
		}
		if (!conv.active()) {
			return ApiException.conflict("messaging.conversation_closed");
		}
		if (conv.moderatorsOnly() && !member.role().moderates()) {
			return ApiException.forbidden("messaging.posting_restricted");
		}
		if (conv.direct()) {
			UUID other = otherParticipant(conv.id(), member.userId());
			if (other != null) {
				Block block = blockBetween(member.userId(), other);
				if (block == Block.BY_ME) {
					return ApiException.forbidden("messaging.you_blocked");
				}
				if (block == Block.BY_OTHER) {
					return notAllowed();
				}
			}
		}
		return null;
	}

	/** The other non-observer participant of a DIRECT conversation. */
	public UUID otherParticipant(UUID conversationId, UUID userId) {
		return this.jdbc.sql("""
				select user_id from conversation_members
				where conversation_id = :c and left_at is null and member_role <> 'OBSERVER' and user_id <> :u""")
			.param("c", conversationId)
			.param("u", userId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	public enum Block {

		NONE, BY_ME, BY_OTHER

	}

	/** Whether either of the two blocked the other (a block by {@code me} wins when both did). */
	public Block blockBetween(UUID me, UUID other) {
		List<UUID> blockers = this.jdbc.sql("""
				select blocker_id from user_blocks
				where (blocker_id = :a and blocked_id = :b) or (blocker_id = :b and blocked_id = :a)""")
			.param("a", me)
			.param("b", other)
			.query(UUID.class)
			.list();
		if (blockers.contains(me)) {
			return Block.BY_ME;
		}
		return blockers.isEmpty() ? Block.NONE : Block.BY_OTHER;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// starting a DIRECT conversation

	/** One participant of a new DIRECT conversation. */
	public record Party(UUID userId, ActingAs actingAs, UUID onBehalfOfStudentId) {
	}

	/** Which kind of DIRECT conversation was asked for and who is who. {@code studentId} is the thread's child (null for student-student, guardian-guardian uses the caller's child). */
	public record DirectPlan(DirectKind kind, UUID schoolId, UUID contextStudentId, Party caller, Party recipient) {

		/** One thread per kind and pair (and child): the unique key of the conversation. */
		public String key() {
			Party guardian = guardianParty();
			return switch (this.kind) {
				case GUARDIAN_TEACHER -> "GT:" + guardian.userId() + ":" + teacherParty().userId() + ":" + this.contextStudentId;
				case TEACHER_STUDENT -> "TS:" + teacherParty().userId() + ":" + this.contextStudentId;
				case GUARDIAN_GUARDIAN, STUDENT_STUDENT -> {
					String a = this.caller.userId().toString();
					String b = this.recipient.userId().toString();
					String prefix = (this.kind == DirectKind.GUARDIAN_GUARDIAN) ? "GG:" : "SS:";
					yield prefix + ((a.compareTo(b) < 0) ? a + ":" + b : b + ":" + a);
				}
			};
		}

		private Party guardianParty() {
			return (this.caller.actingAs() == ActingAs.GUARDIAN) ? this.caller : this.recipient;
		}

		private Party teacherParty() {
			return (this.caller.actingAs() == ActingAs.TEACHER) ? this.caller : this.recipient;
		}

	}

	/**
	 * Decides which kind of DIRECT conversation {@code caller} asks for with {@code recipient} (about {@code contextStudentId}), or
	 * refuses with 403 {@code messaging.not_allowed}. See the rule book R2-R4. The live eligibility of both sides and the school
	 * settings of the kind are checked by the caller on the created rows, in the same transaction.
	 */
	public DirectPlan planDirect(CurrentUser caller, UUID schoolId, UUID recipientId, UUID contextStudentId) {
		UUID me = caller.userId();
		if (recipientId == null || recipientId.equals(me)) {
			throw notAllowed();
		}
		boolean callerStudent = this.people.isActiveStudent(schoolId, me);
		boolean recipientStudent = this.people.isActiveStudent(schoolId, recipientId);
		boolean callerTeacher = this.people.isActiveTeacher(schoolId, me);
		boolean recipientTeacher = this.people.isActiveTeacher(schoolId, recipientId);
		Block block = blockBetween(me, recipientId);
		if (block == Block.BY_ME) {
			throw ApiException.forbidden("messaging.you_blocked");
		}
		if (block == Block.BY_OTHER) {
			throw notAllowed();
		}
		if (callerStudent) {
			// R4: a student never starts a conversation with an adult unless the school lets teacher-student start from either side
			if (recipientStudent) {
				return new DirectPlan(DirectKind.STUDENT_STUDENT, schoolId, null, new Party(me, ActingAs.STUDENT, null),
						new Party(recipientId, ActingAs.STUDENT, null));
			}
			if (recipientTeacher && this.settings.studentMayStartTeacherDirect(schoolId)) {
				return new DirectPlan(DirectKind.TEACHER_STUDENT, schoolId, me, new Party(me, ActingAs.STUDENT, null),
						new Party(recipientId, ActingAs.TEACHER, null));
			}
			throw notAllowed();
		}
		if (recipientStudent) {
			if (!callerTeacher) {
				throw notAllowed();
			}
			return new DirectPlan(DirectKind.TEACHER_STUDENT, schoolId, recipientId, new Party(me, ActingAs.TEACHER, null),
					new Party(recipientId, ActingAs.STUDENT, null));
		}
		if (contextStudentId == null) {
			throw ApiException.badRequest("messaging.context_student_required");
		}
		if (!this.people.isActiveStudent(schoolId, contextStudentId)) {
			throw notAllowed();
		}
		boolean callerGuardian = this.guardians.can(me, contextStudentId, GuardianPermission.MESSAGE_STAFF);
		boolean recipientGuardian = this.guardians.can(recipientId, contextStudentId, GuardianPermission.MESSAGE_STAFF);
		if (callerGuardian && recipientTeacher && teaches(schoolId, recipientId, contextStudentId)) {
			return new DirectPlan(DirectKind.GUARDIAN_TEACHER, schoolId, contextStudentId,
					new Party(me, ActingAs.GUARDIAN, contextStudentId), new Party(recipientId, ActingAs.TEACHER, null));
		}
		if (callerTeacher && recipientGuardian && teaches(schoolId, me, contextStudentId)) {
			return new DirectPlan(DirectKind.GUARDIAN_TEACHER, schoolId, contextStudentId, new Party(me, ActingAs.TEACHER, null),
					new Party(recipientId, ActingAs.GUARDIAN, contextStudentId));
		}
		if (callerGuardian) {
			UUID theirChild = classmateChildOfGuardian(schoolId, recipientId, contextStudentId);
			if (theirChild != null) {
				return new DirectPlan(DirectKind.GUARDIAN_GUARDIAN, schoolId, contextStudentId,
						new Party(me, ActingAs.GUARDIAN, contextStudentId), new Party(recipientId, ActingAs.GUARDIAN, theirChild));
			}
		}
		throw notAllowed();
	}

	/** True when the teacher is an ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER of a class in which the student is ACTIVE. */
	public boolean teaches(UUID schoolId, UUID teacherId, UUID studentId) {
		return this.jdbc.sql("""
				select exists (select 1 from class_teachers ct join class_students cs on cs.class_id = ct.class_id and cs.status = 'ACTIVE'
				               where ct.teacher_id = :t and ct.school_id = :s and ct.status = 'ACTIVE'
				                 and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER') and cs.student_id = :st)""")
			.param("t", teacherId)
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	/** A child of {@code guardianId} (ACTIVE, unrestricted, {@code can_message_staff}) who is ACTIVE in a class with {@code studentId}. */
	private UUID classmateChildOfGuardian(UUID schoolId, UUID guardianId, UUID studentId) {
		return this.jdbc.sql("""
				select g.student_id from student_guardians g
				join school_students ss on ss.student_id = g.student_id and ss.school_id = :s and ss.status = 'ACTIVE'
				join class_students cb on cb.student_id = g.student_id and cb.status = 'ACTIVE'
				join class_students ca on ca.class_id = cb.class_id and ca.student_id = :st and ca.status = 'ACTIVE'
				where g.guardian_id = :g and g.status = 'ACTIVE' and not g.custody_restricted and g.can_message_staff
				order by g.is_primary desc, g.student_id limit 1""")
			.param("s", schoolId)
			.param("g", guardianId)
			.param("st", studentId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// groups and moderation

	public enum GroupBasis {

		/** A LEAD or CO teacher (or a teacher of the class for student groups) in an allowed role. */
		TEACHER,

		/** A class manager or holder of {@code school.messaging.moderate} in an allowed role. */
		MANAGER,

		/** A student proposing a student group the school lets them propose (it waits for approval). */
		STUDENT_PROPOSAL

	}

	/** The role codes of {@code messaging.group_create.roles} the caller holds in the school (and class). */
	public Set<String> creatorRoles(CurrentUser user, UUID schoolId, UUID classId) {
		Set<String> roles = new HashSet<>();
		if (this.people.isActiveTeacher(schoolId, user.userId())) {
			roles.add("TEACHER");
		}
		if (this.gate.can(user, schoolId, Permissions.SCHOOL_MESSAGING_MODERATE)
				|| (classId != null && this.classes.canManage(user, classId))) {
			roles.add("SCHOOL_ADMIN");
		}
		return roles;
	}

	/** The caller's active teacher role in the class, when it is one that may take part in messaging (not ASSISTANT). */
	public Optional<TeacherRole> messagingTeacherRole(CurrentUser user, UUID classId) {
		return this.classes.membershipOf(user, classId)
			.filter((m) -> m.kind() == Kind.TEACHER)
			.map(ClassMembershipView::teacherRole)
			.filter((r) -> r != TeacherRole.ASSISTANT);
	}

	/** Why the caller may create a group of {@code kind} (for {@code classId} where it is class-bound), or null (never throws). */
	public GroupBasis findGroupBasis(CurrentUser user, UUID schoolId, GroupKind kind, UUID classId) {
		if (kind == GroupKind.SCHOOL_STAFF) {
			if (!this.gate.can(user, schoolId, Permissions.SCHOOL_MESSAGING_MODERATE)) {
				return null;
			}
			return GroupBasis.MANAGER;
		}
		Set<String> roles = creatorRoles(user, schoolId, classId);
		boolean roleAllowed = !Collections.disjoint(roles, this.settings.groupCreateRoles(schoolId));
		if (kind == GroupKind.STUDENT_GROUP && this.people.isActiveStudent(schoolId, user.userId())) {
			if (this.settings.studentsMayProposeGroups(schoolId) && this.classes.membershipOf(user, classId)
				.filter((m) -> m.kind() == Kind.STUDENT)
				.isPresent()) {
				return GroupBasis.STUDENT_PROPOSAL;
			}
			return null;
		}
		if (!roleAllowed) {
			return null;
		}
		boolean manager = classId != null && this.classes.canManage(user, classId);
		boolean moderate = this.gate.can(user, schoolId, Permissions.SCHOOL_MESSAGING_MODERATE);
		Optional<TeacherRole> teacher = (classId != null) ? messagingTeacherRole(user, classId) : Optional.empty();
		switch (kind) {
			case CLASS_STUDENTS, CLASS_PARENTS -> {
				boolean leadOrCo = teacher.filter((r) -> r == TeacherRole.LEAD || r == TeacherRole.CO_TEACHER).isPresent();
				if (leadOrCo && roles.contains("TEACHER")) {
					return GroupBasis.TEACHER;
				}
				if (manager && roles.contains("SCHOOL_ADMIN")) {
					return GroupBasis.MANAGER;
				}
			}
			case STUDENT_GROUP -> {
				if (teacher.isPresent() && roles.contains("TEACHER")) {
					return GroupBasis.TEACHER;
				}
				if (manager && roles.contains("SCHOOL_ADMIN")) {
					return GroupBasis.MANAGER;
				}
			}
			case STAFF -> {
				if (roles.contains("TEACHER")) {
					return GroupBasis.TEACHER;
				}
				if (moderate) {
					return GroupBasis.MANAGER;
				}
			}
			default -> {
			}
		}
		return null;
	}

	/** {@link #findGroupBasis} or 403 {@code messaging.group_not_allowed}. */
	public GroupBasis groupBasis(CurrentUser user, UUID schoolId, GroupKind kind, UUID classId) {
		GroupBasis basis = findGroupBasis(user, schoolId, kind, classId);
		if (basis == null) {
			throw ApiException.forbidden("messaging.group_not_allowed");
		}
		return basis;
	}

	/**
	 * What the caller may do to a conversation's members and settings: an eligible OWNER or MODERATOR member of any group, or,
	 * for a conversation that is not DIRECT, a holder of {@code school.messaging.moderate} or a manager of its class, who
	 * moderates without being able to read. 404 for everybody else who cannot use the conversation, 403 for members who may not moderate.
	 */
	public Moderation moderation(CurrentUser user, UUID conversationId) {
		ConversationRow conv = conversation(conversationId).orElseThrow(MessagingPolicy::notFound);
		requireFeature(conv.schoolId());
		Optional<Access> member = find(user.userId(), conversationId, false);
		Moderation moderation = tryModeration(user, conv, member.orElse(null));
		if (moderation != null) {
			return moderation;
		}
		throw (member.isPresent() || (!conv.direct() && moderatesFromOutside(user, conv)))
				? ApiException.forbidden("messaging.moderation_not_allowed") : notFound();
	}

	/** Like {@link #moderation} but null instead of an exception (see {@link #postRefusal}). */
	public Moderation tryModeration(CurrentUser user, UUID conversationId) {
		ConversationRow conv = conversation(conversationId).orElse(null);
		if (conv == null || !this.settings.featureEnabled(conv.schoolId())) {
			return null;
		}
		return tryModeration(user, conv, find(user.userId(), conversationId, false).orElse(null));
	}

	private Moderation tryModeration(CurrentUser user, ConversationRow conv, Access member) {
		if (conv.direct()) {
			return null;
		}
		if (member != null && member.member().role().moderates()) {
			return new Moderation(conv, member.member());
		}
		return moderatesFromOutside(user, conv) ? new Moderation(conv, null) : null;
	}

	private boolean moderatesFromOutside(CurrentUser user, ConversationRow conv) {
		return this.gate.can(user, conv.schoolId(), Permissions.SCHOOL_MESSAGING_MODERATE)
				|| (conv.classId() != null && this.classes.canManage(user, conv.classId()));
	}

	/** A conversation the caller moderates; {@code member} is null for a moderator who is not a member. */
	public record Moderation(ConversationRow conversation, MemberRow member) {
	}

}
