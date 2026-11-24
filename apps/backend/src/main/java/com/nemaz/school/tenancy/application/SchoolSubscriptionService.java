package com.nemaz.school.tenancy.application;

import java.util.UUID;

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

	SchoolSubscriptionService(SchoolRepository schools, SubscriptionService subscriptions) {
		this.schools = schools;
		this.subscriptions = subscriptions;
	}

	@Transactional
	public SubscriptionView assign(UUID schoolId, AssignSubscription command, UUID actorId) {
		if (!this.schools.existsById(schoolId)) {
			throw ApiException.notFound("school.not_found");
		}
		return this.subscriptions.assignToSchool(schoolId, command, actorId);
	}

}
