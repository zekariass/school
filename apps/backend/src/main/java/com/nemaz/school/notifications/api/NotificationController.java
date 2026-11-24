package com.nemaz.school.notifications.api;

import java.util.UUID;

import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.notifications.application.InboxService;
import com.nemaz.school.notifications.application.NotificationView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's notification inbox. Another user's notification is 404. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "notifications")
public class NotificationController {

	private final InboxService inbox;

	NotificationController(InboxService inbox) {
		this.inbox = inbox;
	}

	@GetMapping
	@Operation(operationId = "listNotifications", summary = "My notifications, newest first (archived ones excluded)")
	public CursorPage<NotificationView> list(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit, @RequestParam(defaultValue = "false") boolean unreadOnly) {
		return this.inbox.list(CurrentUser.require().userId(), unreadOnly, cursor, limit);
	}

	@GetMapping("/unread-count")
	@Operation(operationId = "getUnreadNotificationCount", summary = "How many unread notifications I have")
	public UnreadCount unreadCount() {
		return new UnreadCount(this.inbox.unreadCount(CurrentUser.require().userId()));
	}

	@PostMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "markNotificationRead", summary = "Mark one notification read (idempotent)")
	public void read(@PathVariable UUID id) {
		this.inbox.markRead(CurrentUser.require().userId(), id);
	}

	@PostMapping("/read-all")
	@Operation(operationId = "markAllNotificationsRead", summary = "Mark every unread notification read")
	public MarkedRead readAll() {
		return new MarkedRead(this.inbox.markAllRead(CurrentUser.require().userId()));
	}

	@PostMapping("/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "archiveNotification", summary = "Remove a notification from the inbox (idempotent)")
	public void archive(@PathVariable UUID id) {
		this.inbox.archive(CurrentUser.require().userId(), id);
	}

	@PostMapping("/test")
	@PreAuthorize("@authz.platform('platform.settings.manage')")
	@Operation(operationId = "sendTestNotification",
			summary = "Send myself a test notification (platform administrators): inbox, real-time and push")
	public TestNotificationResponse test() {
		return new TestNotificationResponse(this.inbox.sendTest(CurrentUser.require().userId()));
	}

	public record UnreadCount(@NotNull int count) {
	}

	public record MarkedRead(@NotNull int updated) {
	}

	public record TestNotificationResponse(@NotNull UUID notificationId) {
	}

}
