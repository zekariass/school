package com.nemaz.school.notifications.application;

import java.time.Clock;

import com.nemaz.school.authorization.RoleAssignmentGranted;
import com.nemaz.school.common.security.ScopeLevel;
import com.nemaz.school.identity.SessionsRevoked;
import com.nemaz.school.notifications.LocalizedText;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.notifications.infrastructure.PushDeviceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Events from other modules that this module reacts to. Handlers are idempotent: the outbox
 * delivers at least once.
 */
@Component
class NotificationEvents {

	static final String ROLE_ASSIGNMENT = "ROLE_ASSIGNMENT";

	private static final Logger log = LoggerFactory.getLogger(NotificationEvents.class);

	private final NotificationService notifications;

	private final PushDeviceStore devices;

	private final Clock clock;

	NotificationEvents(NotificationService notifications, PushDeviceStore devices, Clock clock) {
		this.notifications = notifications;
		this.devices = devices;
		this.clock = clock;
	}

	/**
	 * Tells the grantee about their new role. System grants (the bootstrap administrator) and
	 * self-grants are not announced. The deep link is {@code /}: the app lands on its home, which
	 * now shows the new role.
	 */
	@ApplicationModuleListener
	void on(RoleAssignmentGranted event) {
		if (event.grantedBy() == null || event.grantedBy().equals(event.userId())) {
			return;
		}
		if (this.notifications.alreadyNotified(event.userId(), "ROLE_GRANTED", ROLE_ASSIGNMENT, event.assignmentId())) {
			return;
		}
		NotificationRequest.Builder request = NotificationRequest.to("ROLE_GRANTED", event.userId())
			.school(event.schoolId())
			.source(ROLE_ASSIGNMENT, event.assignmentId())
			.path("/");
		LocalizedText role = LocalizedText.of(event.roleNames().isEmpty() ? java.util.Map.of("en", event.roleCode())
				: event.roleNames());
		if (event.scopeLevel() == ScopeLevel.PLATFORM || event.scopeNames().isEmpty()) {
			request.messageKey("notification.role_granted_platform").args(role);
		}
		else {
			request.args(role, LocalizedText.of(event.scopeNames()));
		}
		this.notifications.notify(request.build());
	}

	/** No pushes to phones that signed out: devices bound to a revoked session family are revoked. */
	@ApplicationModuleListener
	void on(SessionsRevoked event) {
		int revoked = this.devices.revokeFamilies(event.userId(), event.familyIds(), this.clock.instant());
		if (revoked > 0) {
			log.info("Revoked {} push devices of signed-out sessions", revoked);
		}
	}

}
