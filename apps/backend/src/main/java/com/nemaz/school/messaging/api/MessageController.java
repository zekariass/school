package com.nemaz.school.messaging.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.messaging.application.MessageQueries;
import com.nemaz.school.messaging.application.MessageService;
import com.nemaz.school.messaging.application.MessageService.SendCommand;
import com.nemaz.school.messaging.application.MessageTranslations;
import com.nemaz.school.messaging.application.MessagingModels.MessageItemView;
import com.nemaz.school.messaging.application.MessagingModels.MessageTranslationView;
import com.nemaz.school.messaging.application.MessagingModels.SendResultView;
import com.nemaz.school.messaging.application.ModerationService;

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
 * The messages of a conversation: send (idempotent by {@code clientMessageId}), read, edit, delete, react, report, hide,
 * translate. Plain text only. Every call re-checks the live eligibility of the caller (rule book R1): a person who lost access
 * gets 404 {@code messaging.conversation_not_found}.
 */
@RestController
@Tag(name = "messaging")
public class MessageController {

	private static final String BASE = "/api/v1/conversations/{conversationId}/messages";

	private final MessageService messages;

	private final MessageQueries queries;

	private final ModerationService moderation;

	private final MessageTranslations translations;

	MessageController(MessageService messages, MessageQueries queries, ModerationService moderation,
			MessageTranslations translations) {
		this.messages = messages;
		this.queries = queries;
		this.moderation = moderation;
		this.translations = translations;
	}

	@GetMapping(BASE)
	@Operation(operationId = "listMessages",
			summary = "Messages of a conversation, newest first, keyset paged. Deleted and hidden messages are tombstones without text or images; messages of people the caller blocked are left out (never for an observer)")
	public CursorPage<MessageItemView> list(@PathVariable UUID conversationId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit) {
		return this.queries.messages(CurrentUser.require(), conversationId, cursor, limit);
	}

	@GetMapping(BASE + "/{messageId}")
	@Operation(operationId = "getMessage", summary = "One message (404 when the caller may not see it)")
	public MessageItemView get(@PathVariable UUID conversationId, @PathVariable UUID messageId) {
		return this.queries.message(CurrentUser.require(), conversationId, messageId);
	}

	@PostMapping(BASE)
	@Operation(operationId = "sendMessage",
			summary = "Sends a message: 201 when new, 200 with the original when this clientMessageId was already stored (also under concurrency). Plain text (control, bidi and zero-width characters are stripped; 1-2000 characters) or MEDIA (1-4 images uploaded first with purpose MESSAGE and context CONVERSATION). sendAt (adults only, 1 minute to 90 days ahead) schedules it. afterHours tells the sender a teacher recipient is outside office hours (delivered without a push). 403 messaging.observer_read_only, messaging.posting_restricted, messaging.you_blocked, messaging.not_allowed; 409 messaging.conversation_closed; 429 messaging.rate_limited (stricter for students)")
	public ResponseEntity<SendResultView> send(@PathVariable UUID conversationId, @Valid @RequestBody SendMessageRequest request) {
		SendResultView result = this.messages.send(CurrentUser.require(), conversationId,
				new SendCommand(request.clientMessageId(), request.messageType(), request.body(), request.bodyLanguageCode(),
						request.mediaIds(), request.replyToMessageId(), request.sendAt()));
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
	}

	@PatchMapping(BASE + "/{messageId}")
	@Operation(operationId = "editMessage",
			summary = "Edits your own text message within messaging.edit_window_minutes (default 15; 409 messaging.edit_window_expired / messaging.edit_disabled). The earlier text is kept in the history")
	public MessageItemView edit(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			@Valid @RequestBody EditMessageRequest request) {
		return this.messages.edit(CurrentUser.require(), conversationId, messageId, request.body());
	}

	@DeleteMapping(BASE + "/{messageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "deleteMessage",
			summary = "Deletes your own message: members see a tombstone, the original text moves to the history that only reviewers can read through the audited endpoint, images are discarded. A scheduled message is withdrawn")
	public void delete(@PathVariable UUID conversationId, @PathVariable UUID messageId) {
		this.messages.delete(CurrentUser.require(), conversationId, messageId);
	}

	@PutMapping(BASE + "/{messageId}/reaction")
	@Operation(operationId = "reactToMessage",
			summary = "Adds your reaction (one per user per emoji, from the fixed set of 12; idempotent). Observers cannot react")
	public MessageItemView react(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			@Valid @RequestBody MessageReactionRequest request) {
		return this.messages.react(CurrentUser.require(), conversationId, messageId, request.emoji());
	}

	@DeleteMapping(BASE + "/{messageId}/reaction")
	@Operation(operationId = "removeMessageReaction", summary = "Removes your reaction (idempotent)")
	public MessageItemView unreact(@PathVariable UUID conversationId, @PathVariable UUID messageId, @RequestParam String emoji) {
		return this.messages.unreact(CurrentUser.require(), conversationId, messageId, emoji);
	}

	@PostMapping(BASE + "/{messageId}/report")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "reportMessage",
			summary = "Any member (observers too) reports a message of somebody else: audit row, event MessageReported (ids only) and a notice to the conversation's moderators and the school's reviewers. 20 a day. Repeating is harmless")
	public void report(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			@Valid @RequestBody ReportMessageRequest request) {
		this.moderation.report(CurrentUser.require(), conversationId, messageId, request.reason(), request.note());
	}

	@PostMapping(BASE + "/{messageId}/hide")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "hideMessage",
			summary = "A moderator hides a message with a reason (audited): members then see a tombstone. Owners and moderators of a group, school.messaging.moderate and class managers; school.messaging.review for conversations they may review")
	public void hide(@PathVariable UUID conversationId, @PathVariable UUID messageId, @Valid @RequestBody HideMessageRequest request) {
		this.moderation.hide(CurrentUser.require(), conversationId, messageId, request.reason());
	}

	@PostMapping(BASE + "/{messageId}/unhide")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "unhideMessage", summary = "Shows a hidden message again (same authority as hiding; audited)")
	public void unhide(@PathVariable UUID conversationId, @PathVariable UUID messageId) {
		this.moderation.unhide(CurrentUser.require(), conversationId, messageId);
	}

	@PostMapping(BASE + "/{messageId}/translation")
	@Operation(operationId = "translateMessage",
			summary = "See translation: off by default (messaging.translation.enabled; 403 messaging.translation_disabled), never for direct conversations between an adult and a student (403 messaging.translation_not_allowed), 503 messaging.translation_unavailable without a provider. Always labelled machine translated")
	public MessageTranslationView translate(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			@RequestParam(required = false) String language) {
		return this.translations.translate(CurrentUser.require(), conversationId, messageId, language);
	}

	// --- requests --------------------------------------------------------------------------------------------------

	@Schema(description = "A message. clientMessageId is generated by the client and makes the send idempotent (also an offline outbox)")
	public record SendMessageRequest(@NotNull(message = "validation.required") UUID clientMessageId,
			@Schema(description = "TEXT or MEDIA; default MEDIA when mediaIds is given, else TEXT") @Size(max = 10, message = "validation.tooLong") String messageType,
			@Size(max = 8000, message = "validation.tooLong") String body,
			@Size(max = 8, message = "validation.tooLong") String bodyLanguageCode,
			@Size(max = 4, message = "validation.tooMany") List<@NotNull UUID> mediaIds, UUID replyToMessageId,
			@Schema(description = "Schedule the message (adults only)") Instant sendAt) {

		@Override
		public String toString() {
			return "SendMessageRequest[redacted]";
		}

	}

	public record EditMessageRequest(@NotNull(message = "validation.required") @Size(max = 8000, message = "validation.tooLong") String body) {

		@Override
		public String toString() {
			return "EditMessageRequest[redacted]";
		}

	}

	public record MessageReactionRequest(@NotNull(message = "validation.required") @Size(max = 16, message = "validation.tooLong") String emoji) {
	}

	public record ReportMessageRequest(
			@NotNull(message = "validation.required") @Schema(allowableValues = { "INAPPROPRIATE", "BULLYING", "PRIVACY", "SAFETY", "SPAM", "OTHER" }) String reason,
			@Size(max = 500, message = "validation.tooLong") String note) {

		@Override
		public String toString() {
			return "ReportMessageRequest[redacted]";
		}

	}

	public record HideMessageRequest(@NotNull(message = "validation.required") @Size(max = 300, message = "validation.tooLong") String reason) {
	}

}
