package com.nemaz.school.messaging.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.web.Idempotent;
import com.nemaz.school.messaging.application.ContactService;
import com.nemaz.school.messaging.application.ConversationService;
import com.nemaz.school.messaging.application.ConversationService.DirectResult;
import com.nemaz.school.messaging.application.ConversationService.GroupCommand;
import com.nemaz.school.messaging.application.ConversationService.UpdateCommand;
import com.nemaz.school.messaging.application.MessageQueries;
import com.nemaz.school.messaging.application.MessagingModels.ContactView;
import com.nemaz.school.messaging.application.MessagingModels.ConversationSummaryView;
import com.nemaz.school.messaging.application.MessagingModels.ConversationView;
import com.nemaz.school.messaging.application.MessagingModels.GroupAdminView;
import com.nemaz.school.messaging.application.MessagingModels.MemberListView;
import com.nemaz.school.messaging.application.MessagingModels.MessageItemView;
import com.nemaz.school.messaging.application.MessagingModels.MessagingCapabilitiesView;
import com.nemaz.school.messaging.application.ReadState;
import com.nemaz.school.messaging.domain.GroupKind;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conversations: the inbox, starting DIRECT conversations and groups, members and settings. The rules are in
 * {@code docs/Messaging_Rules.md}. A conversation the caller cannot use right now is 404 {@code messaging.conversation_not_found};
 * a refusal to start something is the generic 403 {@code messaging.not_allowed}; 403 {@code messaging.feature_disabled} when the
 * school switched messaging off. People are identified by member id inside a conversation, never by user id.
 */
@RestController
@Tag(name = "messaging")
public class ConversationController {

	private static final String SCHOOL = "/api/v1/schools/{schoolId}/messaging";

	private final MessageQueries queries;

	private final ConversationService conversations;

	private final ContactService contacts;

	private final ReadState readState;

	ConversationController(MessageQueries queries, ConversationService conversations, ContactService contacts,
			ReadState readState) {
		this.queries = queries;
		this.conversations = conversations;
		this.contacts = contacts;
		this.readState = readState;
	}

	// --- reads -----------------------------------------------------------------------------------------------------

	@GetMapping("/api/v1/me/conversations")
	@Operation(operationId = "listMyConversations",
			summary = "The caller's inbox: conversations they belong to or observe and may use right now, newest activity first, keyset paged, with unread counts (up to 100) and the last message preview (no body for deleted or hidden messages). Filter by schoolId or by studentId (the child a guardian acts for or a thread is about) to group by child")
	public CursorPage<ConversationSummaryView> inbox(@RequestParam(required = false) UUID schoolId,
			@RequestParam(required = false) UUID studentId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.inbox(CurrentUser.require(), schoolId, studentId, cursor, limit);
	}

	@GetMapping("/api/v1/me/messaging/unread-count")
	@Operation(operationId = "getMessagingUnreadCount", summary = "Unread messages over all conversations the caller may use now")
	public MessagingUnreadCount unread() {
		return new MessagingUnreadCount(this.queries.unreadTotal(CurrentUser.require()));
	}

	public record MessagingUnreadCount(@NotNull int unread) {
	}

	@GetMapping("/api/v1/me/scheduled-messages")
	@Operation(operationId = "listMyScheduledMessages", summary = "The caller's own scheduled messages that are not sent yet")
	public List<MessageItemView> scheduled() {
		return this.queries.scheduled(CurrentUser.require());
	}

	@GetMapping("/api/v1/conversations/{conversationId}")
	@Operation(operationId = "getConversation",
			summary = "One conversation the caller may use now (404 otherwise). guardiansCanSee tells a student whether a guardian can read their direct conversation with an adult")
	public ConversationView get(@PathVariable UUID conversationId) {
		return this.queries.conversation(CurrentUser.require(), conversationId);
	}

	@GetMapping("/api/v1/conversations/{conversationId}/members")
	@Operation(operationId = "listConversationMembers",
			summary = "The people of a conversation as the caller may see them: teachers, staff and students by name, guardians only as role labels, observers marked (their count is observerCount)")
	public MemberListView members(@PathVariable UUID conversationId) {
		return this.queries.members(CurrentUser.require(), conversationId);
	}

	// --- starting ----------------------------------------------------------------------------------------------------

	@GetMapping(SCHOOL + "/capabilities")
	@Operation(operationId = "getMessagingCapabilities",
			summary = "What the caller may start and moderate in the school and the school's messaging limits (edit window, office hours, student images, translation). 404 for callers who cannot see the school, 403 messaging.feature_disabled")
	public MessagingCapabilitiesView capabilities(@PathVariable UUID schoolId) {
		return this.contacts.capabilities(CurrentUser.require(), schoolId);
	}

	@GetMapping(SCHOOL + "/contacts")
	@Operation(operationId = "listMessagingContacts",
			summary = "The only way to find somebody to message, about one child (studentId): a guardian of the child gets the child's teachers (by name) or, with audience=PARENTS and messaging.parent_parent.enabled, the guardians of classmates (labels only); a teacher of the child gets the child's guardians (labels only). Students get 403: they have no directory")
	public List<ContactView> contacts(@PathVariable UUID schoolId, @RequestParam(required = false) UUID studentId,
			@RequestParam(required = false) String audience) {
		return this.contacts.contacts(CurrentUser.require(), schoolId, studentId, audience);
	}

	@PostMapping(SCHOOL + "/conversations/direct")
	@Idempotent
	@Operation(operationId = "startDirectConversation",
			summary = "Starts (201) or returns (200) the DIRECT conversation with another person: guardian-teacher (studentId required, one thread per guardian, teacher and child), guardian-guardian (messaging.parent_parent.enabled, same class), teacher-student (messaging.student.teacher_direct.enabled; a student may start it only when the initiator setting is EITHER) or student-student (messaging.student.student_direct.enabled). Teacher-student conversations get the safeguarding floor: the student's guardians become read-only observers, or the conversation is admin-visible. Every refusal is the generic 403 messaging.not_allowed (403 messaging.you_blocked tells the blocker), 400 messaging.context_student_required")
	public ResponseEntity<ConversationView> startDirect(@PathVariable UUID schoolId,
			@Valid @RequestBody StartDirectConversationRequest request) {
		DirectResult result = this.conversations.startDirect(CurrentUser.require(), schoolId, request.recipientUserId(),
				request.studentId());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.conversation());
	}

	@PostMapping(SCHOOL + "/groups")
	@ResponseStatus(HttpStatus.CREATED)
	@Idempotent
	@Operation(operationId = "createGroupConversation",
			summary = "Creates a group: CLASS_STUDENTS and CLASS_PARENTS (a lead or co-teacher of the class or a class manager; members follow the roster), STAFF, STUDENT_GROUP (teachers; a student may propose one when messaging.student.group_create is STUDENTS_WITH_APPROVAL, then it stays PENDING_APPROVAL), SCHOOL_STAFF (school.messaging.moderate). Roles allowed come from messaging.group_create.roles. 403 messaging.group_not_allowed, 409 messaging.group_exists, 400 messaging.member_not_eligible")
	public GroupAdminView createGroup(@PathVariable UUID schoolId, @Valid @RequestBody CreateGroupConversationRequest request) {
		return this.conversations.createGroup(CurrentUser.require(), schoolId,
				new GroupCommand(request.kind(), request.classId(), request.title(), request.memberUserIds(), request.postingPolicy()));
	}

	@GetMapping(SCHOOL + "/groups")
	@Operation(operationId = "listGroupConversations",
			summary = "Groups of the school the caller moderates (school.messaging.moderate: all), proposed (their own) or approves (lead and co-teachers of the class): metadata only, never messages. Filter by status such as PENDING_APPROVAL")
	public List<GroupAdminView> groups(@PathVariable UUID schoolId, @RequestParam(required = false) String status) {
		return this.conversations.groups(CurrentUser.require(), schoolId, status);
	}

	// --- moderation ------------------------------------------------------------------------------------------------

	@PatchMapping("/api/v1/conversations/{conversationId}")
	@Operation(operationId = "updateConversation",
			summary = "Owners and moderators (and school.messaging.moderate or class managers, who need not be members) change a group's title, posting policy (ALL_MEMBERS or MODERATORS_ONLY) and status (ACTIVE, LOCKED, ARCHIVED). DIRECT conversations have no moderators (403)")
	public GroupAdminView update(@PathVariable UUID conversationId, @Valid @RequestBody UpdateConversationRequest request) {
		return this.conversations.update(CurrentUser.require(), conversationId,
				new UpdateCommand(request.title(), request.postingPolicy(), request.status()));
	}

	@PostMapping("/api/v1/conversations/{conversationId}/members")
	@Operation(operationId = "addConversationMembers",
			summary = "Adds members to a group that is not roster-driven (409 messaging.roster_managed). Each must be eligible for the group (staff for staff groups, students of the class and its teachers for student groups): 400 messaging.member_not_eligible. Audited")
	public MemberListView addMembers(@PathVariable UUID conversationId, @Valid @RequestBody AddConversationMembersRequest request) {
		return this.conversations.addMembers(CurrentUser.require(), conversationId, request.userIds());
	}

	@PostMapping("/api/v1/conversations/{conversationId}/members/{memberId}/remove")
	@Operation(operationId = "removeConversationMember",
			summary = "Removes a member (reason required, audited). Observers cannot be removed. A roster-driven group does not add a removed member back")
	public MemberListView removeMember(@PathVariable UUID conversationId, @PathVariable UUID memberId,
			@Valid @RequestBody RemoveConversationMemberRequest request) {
		return this.conversations.removeMember(CurrentUser.require(), conversationId, memberId, request.reason());
	}

	@PostMapping("/api/v1/conversations/{conversationId}/leave")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "leaveConversation", summary = "Leaves a group (not DIRECT conversations, not as an observer)")
	public void leave(@PathVariable UUID conversationId) {
		this.conversations.leave(CurrentUser.require(), conversationId);
	}

	@PostMapping("/api/v1/conversations/{conversationId}/approve")
	@Operation(operationId = "approveGroupConversation",
			summary = "Approves a student group proposal (lead or co-teacher of the class, or school.messaging.moderate). The class's lead and co-teachers become its moderators")
	public GroupAdminView approve(@PathVariable UUID conversationId) {
		return this.conversations.approve(CurrentUser.require(), conversationId);
	}

	@PostMapping("/api/v1/conversations/{conversationId}/reject")
	@Operation(operationId = "rejectGroupConversation", summary = "Rejects a student group proposal")
	public GroupAdminView reject(@PathVariable UUID conversationId) {
		return this.conversations.reject(CurrentUser.require(), conversationId);
	}

	// --- read state and mute -----------------------------------------------------------------------------------------

	@PostMapping("/api/v1/conversations/{conversationId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "markConversationRead",
			summary = "Moves the read watermark to upToMessageId (default: the newest message); idempotent, never backwards. In DIRECT conversations the other person's messages get a Seen receipt (never for an observer)")
	public void markRead(@PathVariable UUID conversationId, @Valid @RequestBody(required = false) MarkConversationReadRequest request) {
		this.readState.markRead(CurrentUser.require(), conversationId, (request != null) ? request.upToMessageId() : null);
	}

	@PutMapping("/api/v1/conversations/{conversationId}/mute")
	@Operation(operationId = "muteConversation", summary = "Silences pushes of the conversation until the given time (at most a year)")
	public MuteResult mute(@PathVariable UUID conversationId, @Valid @RequestBody MuteConversationRequest request) {
		return new MuteResult(this.readState.mute(CurrentUser.require(), conversationId, request.until()));
	}

	public record MuteResult(@NotNull Instant mutedUntil) {
	}

	@DeleteMapping("/api/v1/conversations/{conversationId}/mute")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "unmuteConversation", summary = "Ends the mute")
	public void unmute(@PathVariable UUID conversationId) {
		this.readState.unmute(CurrentUser.require(), conversationId);
	}

	// --- requests --------------------------------------------------------------------------------------------------

	public record StartDirectConversationRequest(@NotNull(message = "validation.required") UUID recipientUserId,
			@Schema(description = "The child the thread is about; required for guardian-teacher and guardian-guardian conversations") UUID studentId) {
	}

	public record CreateGroupConversationRequest(@NotNull(message = "validation.required") GroupKind kind,
			@Schema(description = "Required for CLASS_STUDENTS, CLASS_PARENTS and STUDENT_GROUP") UUID classId,
			@Size(max = 120, message = "validation.tooLong") String title,
			@Size(max = 200, message = "validation.tooMany") List<@NotNull UUID> memberUserIds,
			@Size(max = 20, message = "validation.tooLong") String postingPolicy) {
	}

	public record UpdateConversationRequest(@Size(max = 120, message = "validation.tooLong") String title,
			@Size(max = 20, message = "validation.tooLong") String postingPolicy,
			@Size(max = 20, message = "validation.tooLong") String status) {
	}

	public record AddConversationMembersRequest(
			@NotNull(message = "validation.required") @Size(min = 1, max = 100, message = "validation.tooMany") List<@NotNull UUID> userIds) {
	}

	public record RemoveConversationMemberRequest(
			@NotNull(message = "validation.required") @Size(max = 200, message = "validation.tooLong") String reason) {
	}

	public record MarkConversationReadRequest(UUID upToMessageId) {
	}

	public record MuteConversationRequest(@NotNull(message = "validation.required") Instant until) {
	}

}
