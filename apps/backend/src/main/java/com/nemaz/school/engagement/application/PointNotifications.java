package com.nemaz.school.engagement.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.engagement.PointContext;
import com.nemaz.school.engagement.PointPolarity;
import com.nemaz.school.engagement.PointTransactionRecorded;
import com.nemaz.school.engagement.PointTransactionType;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.notifications.LocalizedText;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * {@code POINT_AWARDED}: the student and the guardians who receive notifications hear about a positive award (home
 * awards: the child only). Needs-work points and reversals are never pushed. Idempotent (delivery is at-least-once):
 * a recipient who already has the notification of that transaction is skipped. Texts name the child by first name and
 * the skill, never the note.
 */
@Component
class PointNotifications {

	static final String TYPE = "POINT_AWARDED";

	private final NotificationService notifications;

	private final GuardianAccess guardians;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	PointNotifications(NotificationService notifications, GuardianAccess guardians, JdbcClient jdbc, JsonMapper json) {
		this.notifications = notifications;
		this.guardians = guardians;
		this.jdbc = jdbc;
		this.json = json;
	}

	@ApplicationModuleListener
	void on(PointTransactionRecorded event) {
		if (event.type() != PointTransactionType.AWARD || event.polarity() != PointPolarity.POSITIVE) {
			return;
		}
		Set<UUID> recipients = new LinkedHashSet<>();
		recipients.add(event.studentId());
		if (event.context() != PointContext.HOME) {
			for (GuardianGrant grant : this.guardians.guardiansOf(event.studentId())) {
				if (grant.allows(GuardianPermission.RECEIVE_NOTIFICATIONS)) {
					recipients.add(grant.guardianId());
				}
			}
		}
		recipients.removeIf((r) -> r.equals(event.awardedBy())
				|| this.notifications.alreadyNotified(r, TYPE, "point_transaction", event.transactionId()));
		if (recipients.isEmpty()) {
			return;
		}
		String child = this.jdbc.sql("select given_name from users where id = :id")
			.param("id", event.studentId())
			.query(String.class)
			.optional()
			.orElse("");
		String names = this.jdbc.sql("select cast(name_i18n as text) from point_categories where id = :id")
			.param("id", event.categoryId())
			.query(String.class)
			.optional()
			.orElse("{}");
		LocalizedText skill = LocalizedText.of(Names.read(this.json, names));
		for (UUID recipient : recipients) {
			boolean isChild = recipient.equals(event.studentId());
			this.notifications.notify(NotificationRequest.to(TYPE, recipient)
				.args(child, event.points(), skill)
				.school(event.schoolId())
				.source("point_transaction", event.transactionId())
				.path(isChild ? "/points" : "/children")
				.data("studentId", event.studentId().toString())
				.build());
		}
	}

}
