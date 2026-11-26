package com.nemaz.school.messaging.application;

import com.nemaz.school.messaging.MessageReported;
import com.nemaz.school.moderation.ReportIntake;
import com.nemaz.school.moderation.TargetTypes;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Hands the messaging {@link MessageReported} hook to the moderation module (docs/Safeguarding_Rules.md S1.6). Messaging keeps
 * its own {@code message_reports} record and review path; moderation creates the matching report and case. Delivery is at
 * least once: the messaging report id is the dedupe key of {@link ReportIntake#ingest}, so a redelivery changes nothing. The
 * listener lives in this module so that the dependency between the modules runs one way (messaging to moderation). The
 * event carries ids and the reason code only; the reporter's note is never passed on.
 */
@Component
class MessageReportListener {

	private final ReportIntake intake;

	MessageReportListener(ReportIntake intake) {
		this.intake = intake;
	}

	@ApplicationModuleListener
	void on(MessageReported event) {
		this.intake.ingest("message_report:" + event.reportId(), TargetTypes.MESSAGE, event.messageId(), event.reporterId(),
				MessagingModeration.moderationReason(event.reason()), null);
	}

}
