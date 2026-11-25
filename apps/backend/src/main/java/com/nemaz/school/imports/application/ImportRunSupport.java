package com.nemaz.school.imports.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.nemaz.school.imports.application.ImportModel.Job;
import com.nemaz.school.imports.application.RowHandler.JobContext;
import com.nemaz.school.imports.domain.DateParser;
import com.nemaz.school.imports.domain.ImportType;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** What the dry run and the commit have in common: handler lookup, job context, short transactions, notifications. */
@Component
class ImportRunSupport {

	private final Map<ImportType, RowHandler> handlers;

	private final TransactionTemplate each;

	private final NotificationService notifications;

	private final TenantDirectory directory;

	private final Clock clock;

	ImportRunSupport(List<RowHandler> handlers, PlatformTransactionManager transactions, NotificationService notifications,
			TenantDirectory directory, Clock clock) {
		this.handlers = new java.util.EnumMap<>(ImportType.class);
		handlers.forEach((h) -> this.handlers.put(h.type(), h));
		this.each = new TransactionTemplate(transactions);
		this.each.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.notifications = notifications;
		this.directory = directory;
		this.clock = clock;
	}

	RowHandler handler(ImportType type) {
		return this.handlers.get(type);
	}

	/** Runs in its own transaction, whatever the caller (the event listener) has open. */
	void inNewTransaction(Runnable work) {
		this.each.executeWithoutResult((status) -> work.run());
	}

	<T> T inNewTransaction(java.util.function.Supplier<T> work) {
		return this.each.execute((status) -> work.get());
	}

	JobContext context(Job job) {
		LocalDate today = LocalDate.now(this.clock.withZone(java.time.ZoneId.of("Africa/Addis_Ababa")));
		return new JobContext(job.schoolId(), job.id(), job.initiatedBy(), job.externalSystemId(),
				new DateParser.Options(job.options().dateSystemOrDefault(), date1904(job)), today);
	}

	private static boolean date1904(Job job) {
		return Boolean.TRUE.equals(job.detected().get("date1904"));
	}

	/** Tells the initiator (once per job and stage) that a stage finished; the page to open is the import's. */
	void notifyStage(Job job, String stage, String messageKey, Object... args) {
		if (this.notifications.alreadyNotified(job.initiatedBy(), "IMPORT_COMPLETED", stage, job.id())) {
			return;
		}
		String slug = this.directory.school(job.schoolId()).map(TenantDirectory.SchoolRef::slug).orElse(null);
		NotificationRequest.Builder builder = NotificationRequest.to("IMPORT_COMPLETED", job.initiatedBy())
			.school(job.schoolId())
			.messageKey(messageKey)
			.source(stage, job.id())
			.args(args)
			.data("importId", job.id().toString());
		if (slug != null) {
			builder.path("/s/" + slug + "/import/" + job.id());
		}
		this.notifications.notify(builder.build());
	}

}
