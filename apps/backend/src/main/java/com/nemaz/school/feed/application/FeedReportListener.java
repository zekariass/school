package com.nemaz.school.feed.application;

import com.nemaz.school.feed.ContentReported;
import com.nemaz.school.moderation.ReportIntake;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Hands the feed's {@link ContentReported} hook to the moderation module (docs/Safeguarding_Rules.md S1.6). Delivery is at
 * least once: the report id is the dedupe key of {@link ReportIntake#ingest}, so a redelivery changes nothing. The listener lives
 * in this module so that the dependency between the modules runs one way (feed to moderation). Nothing but ids and the reason
 * code is passed on.
 */
@Component
class FeedReportListener {

	private final ReportIntake intake;

	FeedReportListener(ReportIntake intake) {
		this.intake = intake;
	}

	@ApplicationModuleListener
	void on(ContentReported event) {
		this.intake.ingest("content_report:" + event.reportId(), event.contentType(), event.contentId(), event.reporterId(),
				event.reasonCode(), null);
	}

}
