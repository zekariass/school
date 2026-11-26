package com.nemaz.school.moderation.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.safeguarding.SafeguardingLeads;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.stereotype.Component;

/**
 * Every notification of the moderation workflow, in one place so that the rule is easy to see: the texts are GENERIC (no
 * content, no names, no outcome, no case id) and a reporter is only ever told "thank you" and, later, "reviewed". Each send
 * is guarded by {@code alreadyNotified}, so a redelivered event or a repeated call notifies nobody twice.
 */
@Component
class ModerationNotifier {

	private final NotificationService notifications;

	private final SafeguardingLeads leads;

	private final TenantDirectory directory;

	ModerationNotifier(NotificationService notifications, SafeguardingLeads leads, TenantDirectory directory) {
		this.notifications = notifications;
		this.leads = leads;
		this.directory = directory;
	}

	/** The single acknowledgement of a report: nothing but thanks (no path, no data, no case). */
	void acknowledge(UUID reporterId, UUID schoolId, UUID reportId) {
		if (!this.notifications.alreadyNotified(reporterId, "REPORT_ACKNOWLEDGED", "content_report", reportId)) {
			this.notifications.notify(NotificationRequest.to("REPORT_ACKNOWLEDGED", reporterId)
				.school(schoolId)
				.source("content_report", reportId)
				.build());
		}
	}

	/** The single "reviewed" notice at resolution: no outcome, no decision, no names. */
	void reviewed(UUID reporterId, UUID schoolId, UUID reportId) {
		if (!this.notifications.alreadyNotified(reporterId, "REPORT_REVIEWED", "content_report", reportId)) {
			this.notifications.notify(NotificationRequest.to("REPORT_REVIEWED", reporterId)
				.school(schoolId)
				.source("content_report", reportId)
				.build());
		}
	}

	/** HIGH cases alert the school's leads at once (the platform leads when it has none); never the subject or a reporter. */
	void alertLeads(UUID caseId, UUID schoolId, Set<UUID> exclude) {
		Set<UUID> recipients = new LinkedHashSet<>(this.leads.alertRecipients(schoolId));
		recipients.removeAll(exclude);
		recipients.removeIf((u) -> this.notifications.alreadyNotified(u, "SAFEGUARDING_ALERT", "moderation_case", caseId));
		if (recipients.isEmpty()) {
			return;
		}
		String slug = (schoolId != null) ? this.directory.school(schoolId).map(TenantDirectory.SchoolRef::slug).orElse(null) : null;
		this.notifications.notify(NotificationRequest.to("SAFEGUARDING_ALERT", recipients)
			.school(schoolId)
			.source("moderation_case", caseId)
			.path((slug != null) ? "/s/" + slug + "/moderation" : "/moderation")
			.build());
	}

	void assigned(UUID assigneeId, UUID schoolId, UUID assignmentId) {
		if (!this.notifications.alreadyNotified(assigneeId, "CASE_ASSIGNED", "moderation_assignment", assignmentId)) {
			String slug = (schoolId != null) ? this.directory.school(schoolId).map(TenantDirectory.SchoolRef::slug).orElse(null) : null;
			this.notifications.notify(NotificationRequest.to("CASE_ASSIGNED", assigneeId)
				.school(schoolId)
				.source("moderation_assignment", assignmentId)
				.path((slug != null) ? "/s/" + slug + "/moderation" : "/moderation")
				.build());
		}
	}

	/** "Something on your account did not follow the rules": never quotes content, never says who reported. */
	void warned(UUID userId, UUID schoolId, UUID warningId) {
		if (!this.notifications.alreadyNotified(userId, "ACCOUNT_WARNING", "account_warning", warningId)) {
			this.notifications.notify(NotificationRequest.to("ACCOUNT_WARNING", userId)
				.school(schoolId)
				.source("account_warning", warningId)
				.build());
		}
	}

}
