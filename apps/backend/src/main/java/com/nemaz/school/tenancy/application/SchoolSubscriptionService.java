package com.nemaz.school.tenancy.application;

import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.subscriptions.AssignSubscription;
import com.nemaz.school.subscriptions.SubscriptionService;
import com.nemaz.school.subscriptions.SubscriptionView;
import com.nemaz.school.tenancy.infrastructure.SchoolRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual plan assignment for a school (P1). Tenancy owns the school, so it checks the
 * school exists before asking the subscriptions module to assign the plan, in one
 * transaction.
 */
@Service
public class SchoolSubscriptionService {

	private final SchoolRepository schools;

	private final SubscriptionService subscriptions;

	private final AuditLog audit;

	SchoolSubscriptionService(SchoolRepository schools, SubscriptionService subscriptions, AuditLog audit) {
		this.schools = schools;
		this.subscriptions = subscriptions;
		this.audit = audit;
	}

	@Transactional
	public SubscriptionView assign(UUID schoolId, AssignSubscription command, UUID actorId) {
		if (!this.schools.existsById(schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
		SubscriptionView view = this.subscriptions.assignToSchool(schoolId, command, actorId);
		this.audit.record(AuditEntry.of("subscription.assign", AuditOutcome.SUCCESS)
			.target("subscription", view.id())
			.school(schoolId)
			.metadata("planCode", view.planCode())
			.metadata("status", view.status().name())
			.metadata("currentPeriodEnd", (view.currentPeriodEnd() != null) ? view.currentPeriodEnd().toString() : null));
		return view;
	}

}
